import * as cdk from 'aws-cdk-lib';
import { Duration } from 'aws-cdk-lib';
import { Stack } from 'aws-cdk-lib';
import { Peer, Port, SecurityGroup, SubnetType, Vpc } from 'aws-cdk-lib/aws-ec2';
import { CfnCacheCluster, CfnSubnetGroup } from 'aws-cdk-lib/aws-elasticache';
import { ApplicationLoadBalancer, NetworkLoadBalancer } from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import { AwsLogDriver, Cluster, ContainerDependencyCondition, ContainerImage, FargateService, FargateTaskDefinition, Secret } from 'aws-cdk-lib/aws-ecs';
import { ManagedPolicy, PolicyStatement, Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { StringParameter } from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { InfraConfig } from './config';
import { createHlsStorage } from './storage-stack';

export interface ManagedEcsFargateStackProps extends cdk.StackProps {
  readonly config: InfraConfig;
}

// Cross-stack values the App stack imports from the Infra stack.
// Optional LB fields are empty strings when the LB is disabled.
export interface ManagedInfraRefs {
  readonly clusterName: string;
  readonly clusterArn: string;
  readonly taskRoleArn: string;
  readonly executionRoleArn: string;
  readonly logGroupName: string;
  readonly bucketName: string;
  readonly distributionDomainName: string;
  readonly redisUri: string;
  readonly hmacSecretName: string;
  readonly serviceSecurityGroupId: string;
  readonly albArn: string;
  readonly albSecurityGroupId: string;
  readonly nlbArn: string;
  readonly nlbDnsName: string;
  readonly vpcId: string;
  readonly publicSubnetIds: string;
  readonly availabilityZones: string;
}

const EXPORT_PREFIX = 'rtmp-managed-';
const REF_KEYS: (keyof ManagedInfraRefs)[] = [
  'clusterName', 'clusterArn', 'taskRoleArn', 'executionRoleArn', 'logGroupName',
  'bucketName', 'distributionDomainName', 'redisUri', 'hmacSecretName', 'serviceSecurityGroupId',
  'vpcId', 'publicSubnetIds', 'availabilityZones'
];
function exportKey(key: string): string {
  return EXPORT_PREFIX + key;
}

export function exportManagedInfra(scope: Construct, config: InfraConfig, refs: ManagedInfraRefs): void {
  for (const key of REF_KEYS) {
    new cdk.CfnOutput(scope, 'Export' + key, { value: refs[key], exportName: exportKey(key) });
  }
  if (config.enableAlb) {
    new cdk.CfnOutput(scope, 'ExportAlbArn', { value: refs.albArn, exportName: exportKey('albArn') });
    new cdk.CfnOutput(scope, 'ExportAlbSecurityGroupId', { value: refs.albSecurityGroupId, exportName: exportKey('albSecurityGroupId') });
  }
  if (config.enableNlb) {
    new cdk.CfnOutput(scope, 'ExportNlbArn', { value: refs.nlbArn, exportName: exportKey('nlbArn') });
    new cdk.CfnOutput(scope, 'ExportNlbDnsName', { value: refs.nlbDnsName, exportName: exportKey('nlbDnsName') });
  }
}

export function importManagedInfra(scope: Construct, config: InfraConfig): ManagedInfraRefs {
  const refs = {} as Record<keyof ManagedInfraRefs, string>;
  for (const key of REF_KEYS) {
    refs[key] = cdk.Fn.importValue(exportKey(key));
  }
  for (const key of ['albArn', 'albSecurityGroupId', 'nlbArn', 'nlbDnsName'] as (keyof ManagedInfraRefs)[]) {
    refs[key] = '';
  }
  if (config.enableAlb) {
    refs.albArn = cdk.Fn.importValue(exportKey('albArn'));
    refs.albSecurityGroupId = cdk.Fn.importValue(exportKey('albSecurityGroupId'));
  }
  if (config.enableNlb) {
    refs.nlbArn = cdk.Fn.importValue(exportKey('nlbArn'));
    refs.nlbDnsName = cdk.Fn.importValue(exportKey('nlbDnsName'));
  }
  return refs;
}

export function createManagedInfra(scope: Construct, config: InfraConfig): ManagedInfraRefs {
  const storage = createHlsStorage(scope);

  const vpc = new Vpc(scope, 'Vpc', {
    maxAzs: 2,
    natGateways: 0,
    subnetConfiguration: [{ name: 'public', subnetType: SubnetType.PUBLIC }]
  });
  vpc.applyRemovalPolicy(cdk.RemovalPolicy.DESTROY);

  const cluster = new Cluster(scope, 'Cluster', { vpc });
  const logGroup = new LogGroup(scope, 'LogGroup', {
    retention: RetentionDays.THREE_DAYS,
    removalPolicy: cdk.RemovalPolicy.DESTROY
  });

  const serviceSecurityGroup = new SecurityGroup(scope, 'ServiceSecurityGroup', {
    vpc,
    allowAllOutbound: true
  });
  serviceSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(1935), 'RTMP direct demo access');
  serviceSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(8888), 'HTTP direct demo access');

  let redisUri = 'redis://127.0.0.1:6379';
  if (config.enableElastiCache) {
    const redisSecurityGroup = new SecurityGroup(scope, 'RedisSecurityGroup', {
      vpc,
      allowAllOutbound: true
    });
    redisSecurityGroup.addIngressRule(serviceSecurityGroup, Port.tcp(6379), 'Redis from Fargate tasks');

    const subnetGroup = new CfnSubnetGroup(scope, 'RedisSubnetGroup', {
      description: 'RTMP demo Redis public-subnet group',
      subnetIds: vpc.publicSubnets.map(subnet => subnet.subnetId)
    });

    const cache = new CfnCacheCluster(scope, 'RedisCluster', {
      engine: 'redis',
      cacheNodeType: 'cache.t3.micro',
      numCacheNodes: 1,
      vpcSecurityGroupIds: [redisSecurityGroup.securityGroupId],
      cacheSubnetGroupName: subnetGroup.ref
    });
    cache.addDependency(subnetGroup);
    redisUri = `redis://${cache.attrRedisEndpointAddress}:${cache.attrRedisEndpointPort}`;
  } else if (config.desiredAppCount > 1) {
    throw new Error('managed mode with desiredAppCount > 1 requires -c enableElastiCache=true so tasks share Redis state');
  }

  const nlb = config.enableNlb
    ? new NetworkLoadBalancer(scope, 'Nlb', {
        vpc,
        internetFacing: true,
        vpcSubnets: { subnetType: SubnetType.PUBLIC }
      })
    : undefined;

  const alb = config.enableAlb
    ? new ApplicationLoadBalancer(scope, 'Alb', {
        vpc,
        internetFacing: true,
        vpcSubnets: { subnetType: SubnetType.PUBLIC }
      })
    : undefined;

  const executionRole = new Role(scope, 'TaskExecutionRole', {
    assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
    managedPolicies: [ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy')]
  });

  const taskRole = new Role(scope, 'TaskRole', {
    assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com')
  });
  storage.bucket.grantReadWrite(taskRole);
  executionRole.addToPolicy(new PolicyStatement({
    actions: ['ssm:GetParameter', 'ssm:GetParameters'],
    resources: [
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/hmac-secret`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/auth-password`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/max-pending-per-ip`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/max-active-per-ip`
    ]
  }));

  new cdk.CfnOutput(scope, 'PlaybackBaseUrl', { value: `http://${config.rtmpHost}` });
  new cdk.CfnOutput(scope, 'HlsBucketName', { value: storage.bucket.bucketName });
  new cdk.CfnOutput(scope, 'ManagedModeWarning', {
    value: 'Scale desiredCount to 0 or destroy this stack immediately after demos to avoid hourly service costs.'
  });
  new cdk.CfnOutput(scope, 'PublicDnsName', { value: nlb ? nlb.loadBalancerDnsName : alb ? alb.loadBalancerDnsName : '' });

  return {
    clusterName: cluster.clusterName,
    clusterArn: cluster.clusterArn,
    taskRoleArn: taskRole.roleArn,
    executionRoleArn: executionRole.roleArn,
    logGroupName: logGroup.logGroupName,
    bucketName: storage.bucket.bucketName,
    distributionDomainName: storage.distribution.distributionDomainName,
    redisUri,
    hmacSecretName: '/rtmp/demo/hmac-secret',
    serviceSecurityGroupId: serviceSecurityGroup.securityGroupId,
    albArn: alb ? alb.loadBalancerArn : '',
    albSecurityGroupId: alb ? alb.connections.securityGroups[0].securityGroupId : '',
    nlbArn: nlb ? nlb.loadBalancerArn : '',
    nlbDnsName: nlb ? nlb.loadBalancerDnsName : '',
    vpcId: vpc.vpcId,
    publicSubnetIds: cdk.Fn.join(',', vpc.publicSubnets.map(s => s.subnetId)),
    availabilityZones: cdk.Fn.join(',', vpc.availabilityZones)
  };
}

export function createManagedApp(scope: Construct, config: InfraConfig, refs: ManagedInfraRefs): void {
  const vpc = Vpc.fromVpcAttributes(scope, 'AppVpc', {
    vpcId: refs.vpcId,
    availabilityZones: cdk.Fn.split(',', refs.availabilityZones),
    publicSubnetIds: cdk.Fn.split(',', refs.publicSubnetIds)
  });

  const cluster = Cluster.fromClusterAttributes(scope, 'AppCluster', {
    clusterName: refs.clusterName,
    clusterArn: refs.clusterArn,
    vpc
  });

  const executionRole = Role.fromRoleArn(scope, 'TaskExecutionRole', refs.executionRoleArn);
  const taskRole = Role.fromRoleArn(scope, 'TaskRole', refs.taskRoleArn);
  const logGroup = LogGroup.fromLogGroupName(scope, 'LogGroup', refs.logGroupName);
  const secretParameter = StringParameter.fromStringParameterAttributes(scope, 'HmacSecretParameter', {
    parameterName: refs.hmacSecretName,
    simpleName: false
  });
  const authPasswordParameter = StringParameter.fromStringParameterAttributes(scope, 'AuthPasswordParameter', {
    parameterName: '/rtmp/demo/auth-password',
    simpleName: false
  });
  const maxPendingPerIpParameter = StringParameter.fromStringParameterAttributes(scope, 'MaxPendingPerIpParameter', {
    parameterName: '/rtmp/demo/max-pending-per-ip',
    simpleName: false
  });
  const maxActivePerIpParameter = StringParameter.fromStringParameterAttributes(scope, 'MaxActivePerIpParameter', {
    parameterName: '/rtmp/demo/max-active-per-ip',
    simpleName: false
  });
  const serviceSecurityGroup = SecurityGroup.fromSecurityGroupId(scope, 'ServiceSecurityGroup', refs.serviceSecurityGroupId);

  const advertisedRtmpHost = config.enableNlb ? refs.nlbDnsName : config.rtmpHost;

  const taskDefinition = new FargateTaskDefinition(scope, 'AppTask', {
    cpu: 256,
    memoryLimitMiB: 1024,
    executionRole,
    taskRole,
    volumes: [{ name: 'hls' }]
  });

  const appContainer = taskDefinition.addContainer('rtmp-app', {
    image: ContainerImage.fromRegistry(config.appImage),
    secrets: {
      RTMP_HMAC_SECRET: Secret.fromSsmParameter(secretParameter),
      RTMP_AUTH_PASSWORD: Secret.fromSsmParameter(authPasswordParameter),
      RTMP_MAX_PENDING_PER_IP: Secret.fromSsmParameter(maxPendingPerIpParameter),
      RTMP_MAX_ACTIVE_PER_IP: Secret.fromSsmParameter(maxActivePerIpParameter)
    },
    environment: {
      REDIS_URI: refs.redisUri,
      RTMP_SERVER_ID: 'managed-fargate-node',
      RTMP_PORT: '1935',
      RTMP_ENDPOINT: `rtmp://${advertisedRtmpHost}:1935/live`,
      RTMP_PLAYBACK_BASE_URL: `http://${advertisedRtmpHost}`,
      RTMP_HLS_CDN_URL: `https://${refs.distributionDomainName}`,
      RTMP_HLS_ROOT: '/app/hls',
      RTMP_HLS_BUCKET: refs.bucketName,
      RTMP_HLS_REGION: Stack.of(scope).region,
      RTMP_AUTH_USERNAME: config.rtmpAuthUsername
    },
    logging: new AwsLogDriver({ streamPrefix: 'app', logGroup })
  });
  appContainer.addPortMappings({ containerPort: 8888 }, { containerPort: 1935 });
  appContainer.addMountPoints({ containerPath: '/app/hls', sourceVolume: 'hls', readOnly: false });

  if (!config.enableElastiCache) {
    const redisContainer = taskDefinition.addContainer('redis', {
      image: ContainerImage.fromRegistry('redis:7-alpine'),
      memoryReservationMiB: 128,
      healthCheck: {
        command: ['CMD-SHELL', 'redis-cli ping | grep PONG'],
        interval: Duration.seconds(5),
        timeout: Duration.seconds(3),
        retries: 5,
        startPeriod: Duration.seconds(5)
      },
      logging: new AwsLogDriver({ streamPrefix: 'redis', logGroup })
    });
    redisContainer.addPortMappings({ containerPort: 6379 });
    appContainer.addContainerDependencies({
      container: redisContainer,
      condition: ContainerDependencyCondition.HEALTHY
    });
  }

  const service = new FargateService(scope, 'AppService', {
    cluster,
    taskDefinition,
    desiredCount: config.desiredAppCount,
    assignPublicIp: true,
    circuitBreaker: { rollback: false },
    securityGroups: [serviceSecurityGroup],
    vpcSubnets: { subnetType: SubnetType.PUBLIC }
  });

  if (config.enableNlb) {
    const nlb = NetworkLoadBalancer.fromNetworkLoadBalancerAttributes(scope, 'Nlb', {
      loadBalancerArn: refs.nlbArn
    });
    const listener = nlb.addListener('RtmpListener', { port: 1935 });
    listener.addTargets('RtmpTargets', {
      port: 1935,
      targets: [service.loadBalancerTarget({ containerName: 'rtmp-app', containerPort: 1935 })]
    });
    new cdk.CfnOutput(scope, 'NlbRtmpUrl', { value: `rtmp://${refs.nlbDnsName}:1935/live` });
  }

  if (config.enableAlb) {
    const alb = ApplicationLoadBalancer.fromApplicationLoadBalancerAttributes(scope, 'Alb', {
      loadBalancerArn: refs.albArn,
      securityGroupId: refs.albSecurityGroupId
    });
    const listener = alb.addListener('HttpListener', { port: 80, open: true });
    listener.addTargets('AppTargets', {
      port: 8888,
      targets: [service.loadBalancerTarget({ containerName: 'rtmp-app', containerPort: 8888 })],
      healthCheck: { path: '/', port: '8888' }
    });
    new cdk.CfnOutput(scope, 'AlbUrl', { value: `http://${alb.loadBalancerDnsName}/` });
  }
}

export class ManagedEcsFargateInfraStack extends Stack {
  constructor(scope: Construct, id: string, props: ManagedEcsFargateStackProps) {
    super(scope, id, props);
    const refs = createManagedInfra(this, props.config);
    exportManagedInfra(this, props.config, refs);
  }
}

export class ManagedEcsFargateAppStack extends Stack {
  constructor(scope: Construct, id: string, props: ManagedEcsFargateStackProps) {
    super(scope, id, props);
    createManagedApp(this, props.config, importManagedInfra(this, props.config));
  }
}

export class ManagedEcsFargateStack extends Stack {
  constructor(scope: Construct, id: string, props: ManagedEcsFargateStackProps) {
    super(scope, id, props);
    const refs = createManagedInfra(this, props.config);
    createManagedApp(this, props.config, refs);
  }
}