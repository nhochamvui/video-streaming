import { ECSClient, DescribeServicesCommand, UpdateServiceCommand } from '@aws-sdk/client-ecs';

const ecs = new ECSClient({});
const CLUSTER_NAME = process.env.CLUSTER_NAME ?? 'rtmp-cheap';
const SERVICE_NAME = process.env.SERVICE_NAME ?? 'rtmp-app-service';
const MAX_APP_COUNT = parseInt(process.env.MAX_APP_COUNT ?? '3', 10);

interface ScaleSignal {
  freeSlots?: number;
  hardCap?: number;
}

interface SqsEvent {
  Records?: Array<{ body: string }>;
}

export const handler = async (event: SqsEvent): Promise<void> => {
  for (const record of event.Records ?? []) {
    let signal: ScaleSignal;
    try {
      signal = JSON.parse(record.body) as ScaleSignal;
    } catch (err) {
      console.error('Invalid message body, skipping', err);
      continue;
    }

    const freeSlots = Number(signal.freeSlots ?? 0);
    const hardCap = Number(signal.hardCap ?? 18);
    if (freeSlots >= hardCap) {
      console.log(`Headroom recovered (freeSlots=${freeSlots} >= hardCap=${hardCap}); no scale-out`);
      continue;
    }

    try {
      const described = await ecs.send(new DescribeServicesCommand({
        cluster: CLUSTER_NAME,
        services: [SERVICE_NAME]
      }));
      const service = described.services?.[0];
      if (!service) {
        console.error(`Service ${CLUSTER_NAME}/${SERVICE_NAME} not found`);
        continue;
      }
      const desired = service.desiredCount ?? 0;
      if (desired >= MAX_APP_COUNT) {
        console.log(`At cap (desiredCount=${desired} >= maxAppCount=${MAX_APP_COUNT}); no scale-out`);
        continue;
      }
      await ecs.send(new UpdateServiceCommand({
        cluster: CLUSTER_NAME,
        service: SERVICE_NAME,
        desiredCount: desired + 1
      }));
      console.log(`Scaled out ${CLUSTER_NAME}/${SERVICE_NAME}: desiredCount ${desired} -> ${desired + 1}`);
    } catch (err) {
      console.error('Scale-out failed', err);
      throw err;
    }
  }
};
