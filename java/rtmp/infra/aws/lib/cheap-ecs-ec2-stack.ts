import * as cdk from 'aws-cdk-lib';
import { Stack } from 'aws-cdk-lib';
import { AmazonLinuxCpuType, CfnEIP, CfnEIPAssociation, Instance, InstanceType, MachineImage, Peer, Port, SecurityGroup, SubnetType, UserData, Vpc } from 'aws-cdk-lib/aws-ec2';
import { AmiHardwareType, AsgCapacityProvider, AwsLogDriver, Cluster, ContainerImage, Ec2Service, Ec2TaskDefinition, EcsOptimizedImage, NetworkMode, PlacementConstraint, Secret } from 'aws-cdk-lib/aws-ecs';
import { ManagedPolicy, PolicyStatement, Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { StringParameter } from 'aws-cdk-lib/aws-ssm';
import { AutoScalingGroup } from 'aws-cdk-lib/aws-autoscaling';
import { Construct } from 'constructs';
import * as fs from 'fs';
import * as path from 'path';
import { InfraConfig } from './config';
import { createHlsStorage } from './storage-stack';

const TEMPLATES_DIR = path.join(__dirname, '..', 'templates');
const TRAEFIK_STATIC_CONFIG = fs.readFileSync(path.join(TEMPLATES_DIR, 'traefik.yml'), 'utf8').trimEnd();
const TRAEFIK_DYNAMIC_TEMPLATE = fs.readFileSync(path.join(TEMPLATES_DIR, 'traefik-dynamic.yml'), 'utf8').trimEnd();

export interface CheapEcsEc2StackProps extends cdk.StackProps {
  readonly config: InfraConfig;
}

// Cross-stack values the App stack imports from the Infra stack.
export interface CheapInfraRefs {
  readonly clusterName: string;
  readonly clusterArn: string;
  readonly capacityProviderName: string;
  readonly taskRoleArn: string;
  readonly executionRoleArn: string;
  readonly logGroupName: string;
  readonly bucketName: string;
  readonly distributionDomainName: string;
  readonly redisUri: string;
  readonly hmacSecretName: string;
  readonly vpcId: string;
  readonly publicSubnetIds: string;
  readonly availabilityZones: string;
}

const EXPORT_PREFIX = 'rtmp-cheap-';
const REF_KEYS: (keyof CheapInfraRefs)[] = [
  'clusterName', 'clusterArn', 'capacityProviderName', 'taskRoleArn', 'executionRoleArn',
  'logGroupName', 'bucketName', 'distributionDomainName', 'redisUri', 'hmacSecretName',
  'vpcId', 'publicSubnetIds', 'availabilityZones'
];

function exportKey(key: string): string {
  return EXPORT_PREFIX + key;
}

export function exportCheapInfra(scope: Construct, refs: CheapInfraRefs): void {
  for (const key of REF_KEYS) {
    new cdk.CfnOutput(scope, 'Export' + key, { value: refs[key], exportName: exportKey(key) });
  }
}

export function importCheapInfra(scope: Construct): CheapInfraRefs {
  const refs = {} as Record<keyof CheapInfraRefs, string>;
  for (const key of REF_KEYS) {
    refs[key] = cdk.Fn.importValue(exportKey(key));
  }
  return refs;
}

export function createCheapInfra(scope: Construct, config: InfraConfig): CheapInfraRefs {
  const storage = createHlsStorage(scope);
  const ecsInstanceCount = Math.max(1, config.desiredAppCount);

  const vpc = new Vpc(scope, 'Vpc', {
    maxAzs: 1,
    natGateways: 0,
    subnetConfiguration: [{ name: 'public', subnetType: SubnetType.PUBLIC }]
  });
  vpc.applyRemovalPolicy(cdk.RemovalPolicy.DESTROY);

  const proxySecurityGroup = new SecurityGroup(scope, 'ProxySecurityGroup', {
    vpc,
    allowAllOutbound: true,
    description: 'Cheap RTMP demo Traefik proxy access'
  });
  proxySecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), 'HTTP through Traefik');
  proxySecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(1935), 'RTMP through Traefik');
  proxySecurityGroup.addIngressRule(Peer.ipv4(config.adminCidr), Port.tcp(22), 'Optional SSH from admin CIDR');

  const securityGroup = new SecurityGroup(scope, 'InstanceSecurityGroup', {
    vpc,
    allowAllOutbound: true,
    description: 'Cheap RTMP demo ECS instance access'
  });
  securityGroup.addIngressRule(proxySecurityGroup, Port.tcp(8888), 'HTTP from Traefik proxy');
  securityGroup.addIngressRule(proxySecurityGroup, Port.tcp(1935), 'RTMP from Traefik proxy');
  securityGroup.addIngressRule(Peer.ipv4(config.adminCidr), Port.tcp(22), 'Optional SSH from admin CIDR');
  securityGroup.addIngressRule(Peer.ipv4(config.netdataCidr), Port.tcp(19999), 'Netdata dashboard from admin CIDR');
  proxySecurityGroup.addIngressRule(securityGroup, Port.tcp(6379), 'Redis from ECS app instances');

  const cluster = new Cluster(scope, 'Cluster', { vpc, clusterName: 'rtmp-cheap' });
  const userData = UserData.forLinux();
  userData.addCommands(
    'echo ECS_ENABLE_CONTAINER_METADATA=true >> /etc/ecs/ecs.config',
    'echo ECS_CLUSTER=rtmp-cheap >> /etc/ecs/ecs.config',
    'echo \'ECS_AVAILABLE_LOGGING_DRIVERS=["json-file","awslogs"]\' >> /etc/ecs/ecs.config',
    'yum update -y ecs-init',
    'curl -sSL https://get.netdata.cloud/kickstart.sh -o /tmp/netdata-kickstart.sh',
    'sh /tmp/netdata-kickstart.sh --non-interactive --stable-channel --no-updates --disable-cloud',
    'usermod -aG docker netdata && systemctl restart netdata'
  );

  const instanceRole = new Role(scope, 'EcsInstanceRole', {
    assumedBy: new ServicePrincipal('ec2.amazonaws.com'),
    managedPolicies: [
      ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonEC2ContainerServiceforEC2Role')
    ]
  });

  const autoScalingGroup = new AutoScalingGroup(scope, 'EcsCapacity', {
    vpc,
    instanceType: config.instanceType,
    machineImage: EcsOptimizedImage.amazonLinux2023(ecsAmiHardwareType(config.instanceTypeName)),
    role: instanceRole,
    securityGroup,
    userData,
    minCapacity: ecsInstanceCount,
    maxCapacity: Math.max(config.maxAppCount, ecsInstanceCount),
    vpcSubnets: { subnetType: SubnetType.PUBLIC },
    autoScalingGroupName: 'rtmp-ecs-asg'
  });

  const capacityProvider = new AsgCapacityProvider(scope, 'CapacityProvider', {
    autoScalingGroup,
    enableManagedTerminationProtection: true
  });
  cluster.addAsgCapacityProvider(capacityProvider);

  const logGroup = new LogGroup(scope, 'LogGroup', {
    retention: RetentionDays.THREE_DAYS,
    removalPolicy: cdk.RemovalPolicy.DESTROY
  });

  const executionRole = new Role(scope, 'TaskExecutionRole', {
    assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
    managedPolicies: [ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy')]
  });

  const taskRole = new Role(scope, 'TaskRole', {
    assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com')
  });
  storage.bucket.grantReadWrite(taskRole);
  cluster.grants.taskProtection(taskRole);
  executionRole.addToPolicy(new PolicyStatement({
    actions: ['ssm:GetParameter', 'ssm:GetParameters'],
    resources: [
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/hmac-secret`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/auth-password`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/max-pending-per-ip`,
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/max-active-per-ip`
    ]
  }));

  const proxyRole = new Role(scope, 'ProxyInstanceRole', {
    assumedBy: new ServicePrincipal('ec2.amazonaws.com')
  });
  proxyRole.addToPolicy(new PolicyStatement({
    actions: [
      'autoscaling:DescribeAutoScalingGroups',
      'ec2:DescribeInstances'
    ],
    resources: ['*']
  }));
  proxyRole.addToPolicy(new PolicyStatement({
    actions: ['ssm:GetParameter'],
    resources: [
      `arn:aws:ssm:${Stack.of(scope).region}:${Stack.of(scope).account}:parameter/rtmp/demo/grafana-cloud-*`
    ]
  }));

  const proxyUserData = UserData.forLinux();
  proxyUserData.addCommands(
    'fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile',
    'echo \'/swapfile none swap sw 0 0\' >> /etc/fstab',
    'dnf install -y docker awscli jq',
    'curl -fsSL https://artifacts.grafana.com/gpg.key | gpg --dearmor -o /etc/pki/rpm-gpg/RPM-GPG-KEY-grafana',
    'cat > /etc/yum.repos.d/grafana.repo <<\'REPO\'',
    '[grafana]',
    'name=grafana',
    'baseurl=https://rpm.grafana.com',
    'repo_gpgcheck=1',
    'enabled=1',
    'gpgcheck=1',
    'gpgkey=https://rpm.grafana.com/gpg.key',
    'sslverify=1',
    'sslcacert=/etc/pki/tls/certs/ca-bundle.crt',
    'REPO',
    'dnf install -y alloy',
    'systemctl enable --now docker',
    'mkdir -p /etc/traefik/dynamic /etc/alloy',
    'cat > /etc/traefik/traefik.yml <<\'EOF\'',
    ...TRAEFIK_STATIC_CONFIG.split('\n'),
    'EOF',
    'cat > /etc/traefik/ecs-base.yml <<\'EOF\'',
    ...TRAEFIK_DYNAMIC_TEMPLATE.split('\n'),
    'EOF',
    'docker run -d --name redis --restart unless-stopped -p 6379:6379 redis:7-alpine',
    'docker run -d --name traefik --restart unless-stopped --network host -v /etc/traefik:/etc/traefik:ro traefik:v3.7',

    'cat > /etc/alloy/config.alloy <<\'EOF\'',
    'logging {}',
    'EOF',
    'chmod 644 /etc/alloy/config.alloy',
    'systemctl enable --now alloy',

    'cat > /usr/local/bin/render-alloy-env <<\'EOF\'',
    '#!/bin/bash',
    'set -euo pipefail',
    `REGION="${Stack.of(scope).region}"`,
    'CREDS_FILE="/etc/alloy/grafana.env"',
    'mkdir -p /etc/alloy',
    'PROM_URL="$(aws ssm get-parameter --region "$REGION" --name /rtmp/demo/grafana-cloud-prom-url --query Parameter.Value --output text 2>/dev/null || echo "")"',
    'PROM_USER="$(aws ssm get-parameter --region "$REGION" --name /rtmp/demo/grafana-cloud-prom-user --query Parameter.Value --output text 2>/dev/null || echo "")"',
    'PROM_TOKEN="$(aws ssm get-parameter --region "$REGION" --with-decryption --name /rtmp/demo/grafana-cloud-prom-token --query Parameter.Value --output text 2>/dev/null || echo "")"',
    'cat > "$CREDS_FILE" <<CREDS',
    'GRAFANA_PROM_URL=$PROM_URL',
    'GRAFANA_PROM_USER=$PROM_USER',
    'GRAFANA_PROM_TOKEN=$PROM_TOKEN',
    'CREDS',
    'EOF',
    'chmod +x /usr/local/bin/render-alloy-env',
    'bash /usr/local/bin/render-alloy-env',

    'cat > /usr/local/bin/render-alloy-config <<\'EOF\'',
    '#!/bin/bash',
    'set -euo pipefail',
    'PRIVATE_IPS="${1:-}"',
    'CREDS="/etc/alloy/grafana.env"',
    'CFG="/etc/alloy/config.alloy"',
    'TMP="$(mktemp)"',
    'GRAFANA_PROM_URL=""',
    'GRAFANA_PROM_USER=""',
    'GRAFANA_PROM_TOKEN=""',
    '[ -f "$CREDS" ] && source "$CREDS"',
    'if [ -z "$GRAFANA_PROM_URL" ]; then',
    '  printf \'logging {}\\n\\nprometheus.scrape \\"disabled\\" {\\n  targets = [{ \\"__address__\\" = \\"127.0.0.1:1\\", \\"job\\" = \\"disabled\\" }]\\n  forward_to = []\\n}\\n\' > "$TMP"',
    'else',
    '  printf \'logging {}\\n\\nprometheus.scrape \\"rtmp\\" {\\n  targets = [\\n\' > "$TMP"',
    '  FIRST=1',
    '  for ip in $PRIVATE_IPS; do',
    '    if [ "$FIRST" -eq 1 ]; then',
    '      printf \'      { \\"__address__\\" = \\"%s:8888\\", \\"job\\" = \\"rtmp-server\\" },\\n\' "$ip" >> "$TMP"',
    '      FIRST=0',
    '    else',
    '      printf \'    , { \\"__address__\\" = \\"%s:8888\\", \\"job\\" = \\"rtmp-server\\" },\\n\' "$ip" >> "$TMP"',
    '    fi',
    '  done',
    '  printf \'  ]\\n  forward_to = [prometheus.remote_write.cloud.receiver]\\n}\\n\\nprometheus.remote_write \\"cloud\\" {\\n  endpoint {\\n    url             = \\"%s\\"\\n    send_exemplars  = true\\n    basic_auth {\\n      username = \\"%s\\"\\n      password = \\"%s\\"\\n    }\\n  }\\n}\\n\' "$GRAFANA_PROM_URL" "$GRAFANA_PROM_USER" "$GRAFANA_PROM_TOKEN" >> "$TMP"',
    'fi',
    'if ! cmp -s "$TMP" "$CFG"; then mv "$TMP" "$CFG"; chmod 644 "$CFG"; systemctl restart alloy; else rm "$TMP"; fi',
    'EOF',
    'chmod +x /usr/local/bin/render-alloy-config',

    'cat > /usr/local/bin/render-traefik-backends <<\'EOF\'',
    '#!/bin/bash',
    'set -euo pipefail',
    `REGION="${Stack.of(scope).region}"`,
    `ASG_NAME="${autoScalingGroup.autoScalingGroupName}"`,
    'BASE_FILE="/etc/traefik/ecs-base.yml"',
    'TMP_FILE="$(mktemp)"',
    'FINAL_FILE="/etc/traefik/dynamic/ecs.yml"',
    'INSTANCE_IDS="$(aws autoscaling describe-auto-scaling-groups --region "$REGION" --auto-scaling-group-names "$ASG_NAME" --query \'AutoScalingGroups[0].Instances[?LifecycleState==`InService`].InstanceId\' --output text)"',
    'PRIVATE_IPS=""',
    'if [ -n "$INSTANCE_IDS" ]; then',
    '  PRIVATE_IPS="$(aws ec2 describe-instances --region "$REGION" --instance-ids $INSTANCE_IDS --query \'Reservations[].Instances[?State.Name==`running`].PrivateIpAddress\' --output text)"',
    'fi',
    'HTTP_LIST="$(mktemp)"',
    'TCP_LIST="$(mktemp)"',
    'if [ -z "$PRIVATE_IPS" ]; then',
    '  echo "          - url: http://127.0.0.1:65535" > "$HTTP_LIST"',
    '  echo "          - address: 127.0.0.1:65535" > "$TCP_LIST"',
    'else',
    '  for ip in $PRIVATE_IPS; do echo "          - url: http://$ip:8888" >> "$HTTP_LIST"; done',
    '  for ip in $PRIVATE_IPS; do echo "          - address: $ip:1935" >> "$TCP_LIST"; done',
    'fi',
    `sed -e '/^#HTTP_SERVERS$/r '"$HTTP_LIST" -e '/^#HTTP_SERVERS$/d' -e '/^#TCP_SERVERS$/r '"$TCP_LIST" -e '/^#TCP_SERVERS$/d' "$BASE_FILE" > "$TMP_FILE"`,
    'rm -f "$HTTP_LIST" "$TCP_LIST"',
    'if ! cmp -s "$TMP_FILE" "$FINAL_FILE"; then mv "$TMP_FILE" "$FINAL_FILE"; else rm "$TMP_FILE"; fi',
    '/usr/local/bin/render-alloy-config "$PRIVATE_IPS"',
    'EOF',
    'chmod +x /usr/local/bin/render-traefik-backends',

    'cat > /etc/systemd/system/traefik-backends.service <<\'EOF\'',
    '[Unit]',
    'Description=Render Traefik ECS backend config',
    '',
    '[Service]',
    'Type=oneshot',
    'ExecStart=/usr/local/bin/render-traefik-backends',
    'EOF',
    'cat > /etc/systemd/system/traefik-backends.timer <<\'EOF\'',
    '[Unit]',
    'Description=Refresh Traefik ECS backend config',
    '',
    '[Timer]',
    'OnBootSec=20s',
    'OnUnitActiveSec=15s',
    'Unit=traefik-backends.service',
    '',
    '[Install]',
    'WantedBy=timers.target',
    'EOF',
    'systemctl daemon-reload',
    'systemctl enable --now traefik-backends.timer'
  );

  const proxyInstance = new Instance(scope, 'ProxyInstance', {
    vpc,
    instanceType: new InstanceType('t4g.nano'),
    machineImage: MachineImage.latestAmazonLinux2023({ cpuType: AmazonLinuxCpuType.ARM_64 }),
    role: proxyRole,
    securityGroup: proxySecurityGroup,
    userData: proxyUserData,
    vpcSubnets: { subnetType: SubnetType.PUBLIC },
    instanceName: 'rtmp-proxy'
  });

  const proxyElasticIp = new CfnEIP(scope, 'ProxyEip', { domain: 'vpc' });
  new CfnEIPAssociation(scope, 'ProxyElasticIpAssociation', {
    allocationId: proxyElasticIp.attrAllocationId,
    instanceId: proxyInstance.instanceId
  });

  new cdk.CfnOutput(scope, 'ProxyElasticIp', { value: proxyElasticIp.ref });
  new cdk.CfnOutput(scope, 'ProxyPublicIp', { value: proxyElasticIp.attrPublicIp });
  new cdk.CfnOutput(scope, 'ProxyInstanceId', { value: proxyInstance.instanceId });
  new cdk.CfnOutput(scope, 'AutoScalingGroupName', { value: autoScalingGroup.autoScalingGroupName });
  new cdk.CfnOutput(scope, 'PlaybackBaseUrl', { value: `http://${config.rtmpHost}` });
  new cdk.CfnOutput(scope, 'HlsBucketName', { value: storage.bucket.bucketName });
  new cdk.CfnOutput(scope, 'DomainConfiguration', { value: `Point ${config.rtmpHost} to Elastic IP ${proxyElasticIp.ref}` });

  StringParameter.fromStringParameterName(scope, 'GrafanaCloudPromUrl',
    '/rtmp/demo/grafana-cloud-prom-url');
  StringParameter.fromStringParameterName(scope, 'GrafanaCloudPromUser',
    '/rtmp/demo/grafana-cloud-prom-user');
  StringParameter.fromStringParameterName(scope, 'GrafanaCloudPromToken',
    '/rtmp/demo/grafana-cloud-prom-token');

  return {
    clusterName: cluster.clusterName,
    clusterArn: cluster.clusterArn,
    capacityProviderName: capacityProvider.capacityProviderName,
    taskRoleArn: taskRole.roleArn,
    executionRoleArn: executionRole.roleArn,
    logGroupName: logGroup.logGroupName,
    bucketName: storage.bucket.bucketName,
    distributionDomainName: storage.distribution.distributionDomainName,
    redisUri: `redis://${proxyInstance.instancePrivateIp}:6379`,
    hmacSecretName: '/rtmp/demo/hmac-secret',
    vpcId: vpc.vpcId,
    publicSubnetIds: cdk.Fn.join(',', vpc.publicSubnets.map(s => s.subnetId)),
    availabilityZones: cdk.Fn.join(',', vpc.availabilityZones)
  };
}

export function createCheapApp(scope: Construct, config: InfraConfig, refs: CheapInfraRefs): void {
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

  const taskDefinition = new Ec2TaskDefinition(scope, 'AppTask', {
    networkMode: NetworkMode.HOST,
    executionRole,
    taskRole,
    volumes: [{ name: 'hls' }],
    family: 'rtmp-app-task-def'
  });
  const appContainer = taskDefinition.addContainer('rtmp-app', {
    image: ContainerImage.fromRegistry(config.appImage),
    memoryReservationMiB: 384,
    memoryLimitMiB: 640,
    secrets: {
      RTMP_HMAC_SECRET: Secret.fromSsmParameter(secretParameter),
      RTMP_AUTH_PASSWORD: Secret.fromSsmParameter(authPasswordParameter),
      RTMP_MAX_PENDING_PER_IP: Secret.fromSsmParameter(maxPendingPerIpParameter),
      RTMP_MAX_ACTIVE_PER_IP: Secret.fromSsmParameter(maxActivePerIpParameter)
    },
    environment: {
      MICRONAUT_SERVER_PORT: '8888',
      REDIS_URI: refs.redisUri,
      RTMP_SERVER_ID: 'auto',
      RTMP_PORT: '1935',
      RTMP_ENDPOINT: `rtmp://${config.rtmpHost}:1935/live`,
      RTMP_PLAYBACK_BASE_URL: `http://${config.rtmpHost}`,
      RTMP_HLS_CDN_URL: `https://${refs.distributionDomainName}`,
      RTMP_HLS_ROOT: '/app/hls',
      RTMP_HLS_BUCKET: refs.bucketName,
      RTMP_HLS_REGION: Stack.of(scope).region,
      RTMP_AUTH_USERNAME: config.rtmpAuthUsername
    },
    logging: new AwsLogDriver({ streamPrefix: 'app', logGroup })
  });
  appContainer.addPortMappings(
    { containerPort: 8888, hostPort: 8888 },
    { containerPort: 1935, hostPort: 1935 }
  );
  appContainer.addMountPoints({ containerPath: '/app/hls', sourceVolume: 'hls', readOnly: false });

  const appService = new Ec2Service(scope, 'AppService', {
    cluster,
    taskDefinition,
    serviceName: 'rtmp-app-service',
    desiredCount: Math.max(1, config.desiredAppCount),
    circuitBreaker: { rollback: false },
    minHealthyPercent: 0,
    maxHealthyPercent: 100,
    placementConstraints: [PlacementConstraint.distinctInstances()],
    capacityProviderStrategies: [{ capacityProvider: refs.capacityProviderName, weight: 1 }]
  });

  const scaling = appService.autoScaleTaskCount({
    minCapacity: Math.max(1, config.desiredAppCount),
    maxCapacity: config.maxAppCount
  });
  scaling.scaleOnCpuUtilization('CpuScaling', {
    targetUtilizationPercent: 90,
    scaleInCooldown: cdk.Duration.minutes(15),
    scaleOutCooldown: cdk.Duration.minutes(2)
  });
  scaling.scaleOnMemoryUtilization('MemoryScaling', {
    targetUtilizationPercent: 90,
    disableScaleIn: true
  });

  new cdk.CfnOutput(scope, 'HttpUrl', { value: `http://${config.rtmpHost}/` });
  new cdk.CfnOutput(scope, 'RtmpUrl', { value: `rtmp://${config.rtmpHost}:1935/live` });
}

export class CheapEcsEc2InfraStack extends Stack {
  constructor(scope: Construct, id: string, props: CheapEcsEc2StackProps) {
    super(scope, id, props);
    const refs = createCheapInfra(this, props.config);
    exportCheapInfra(this, refs);
  }
}

export class CheapEcsEc2AppStack extends Stack {
  constructor(scope: Construct, id: string, props: CheapEcsEc2StackProps) {
    super(scope, id, props);
    createCheapApp(this, props.config, importCheapInfra(this));
  }
}

export class CheapEcsEc2Stack extends Stack {
  constructor(scope: Construct, id: string, props: CheapEcsEc2StackProps) {
    super(scope, id, props);
    const refs = createCheapInfra(this, props.config);
    createCheapApp(this, props.config, refs);
  }
}

function ecsAmiHardwareType(instanceTypeName: string): AmiHardwareType {
  const family = instanceTypeName.split('.')[0].toLowerCase();
  return family === 'a1' || family.endsWith('g') ? AmiHardwareType.ARM : AmiHardwareType.STANDARD;
}
