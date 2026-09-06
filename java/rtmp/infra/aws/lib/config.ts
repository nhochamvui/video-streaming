import * as cdk from 'aws-cdk-lib';
import { InstanceType } from 'aws-cdk-lib/aws-ec2';

export type DeployMode = 'cheap' | 'managed';

export interface InfraConfig {
  readonly deployMode: DeployMode;
  readonly appImage: string;
  readonly instanceTypeName: string;
  readonly instanceType: InstanceType;
  readonly desiredAppCount: number;
  readonly maxAppCount: number;
  readonly enableAlb: boolean;
  readonly enableNlb: boolean;
  readonly enableElastiCache: boolean;
  readonly adminCidr: string;
  readonly netdataCidr: string;
  readonly rtmpHost: string;
  readonly rtmpAuthUsername: string;
}

export function getConfig(app: cdk.App): InfraConfig {
  const deployMode = readString(app, 'deployMode', 'cheap') as DeployMode;
  if (deployMode !== 'cheap' && deployMode !== 'managed') {
    throw new Error('deployMode must be "cheap" or "managed"');
  }

  const maxAppCount = readNumber(app, 'maxAppCount', 3);
  const desiredAppCount = Math.min(readNumber(app, 'desiredAppCount', 1), maxAppCount);
  const instanceTypeName = readString(app, 'instanceType', 't4g.micro');

  return {
    deployMode,
    appImage: readString(app, 'appImage', 'public.ecr.aws/docker/library/eclipse-temurin:21-jre'),
    instanceTypeName,
    instanceType: new InstanceType(instanceTypeName),
    desiredAppCount,
    maxAppCount,
    enableAlb: readBoolean(app, 'enableAlb', false),
    enableNlb: readBoolean(app, 'enableNlb', false),
    enableElastiCache: readBoolean(app, 'enableElastiCache', false),
    adminCidr: readString(app, 'adminCidr', '0.0.0.0/0'),
    netdataCidr: readString(app, 'netdataCidr', '42.117.146.241/32'),
    rtmpHost: readString(app, 'rtmpHost', 'replace-after-deploy.example.com'),
    rtmpAuthUsername: readString(app, 'rtmpAuthUsername', 'admin')
  };
}

function readString(app: cdk.App, key: string, defaultValue: string): string {
  const value = app.node.tryGetContext(key);
  return value === undefined || value === null || value === '' ? defaultValue : String(value);
}

function readNumber(app: cdk.App, key: string, defaultValue: number): number {
  const value = app.node.tryGetContext(key);
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`${key} must be a non-negative integer`);
  }
  return parsed;
}

function readBoolean(app: cdk.App, key: string, defaultValue: boolean): boolean {
  const value = app.node.tryGetContext(key);
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }
  if (typeof value === 'boolean') {
    return value;
  }
  return ['1', 'true', 'yes', 'y'].includes(String(value).toLowerCase());
}
