import grpc from 'k6/net/grpc';
import { check } from 'k6';

// Нагрузка на слой хранения напрямую (без доменного слоя).
// Канал gRPC переиспользуется на протяжении жизни VU.
const client = new grpc.Client();
client.load(['/proto'], 'storage.proto');
let connected = false;

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 40),
      duration: __ENV.DURATION || '25s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const replicas = Number(__ENV.REPLICAS || 1);
  const target =
    replicas > 1
      ? `arch-labs-storage-${1 + (__VU % replicas)}:50051`
      : 'storage:50051';
  const user = `s${__VU}-${__ITER}-${Date.now()}`;
  const now = new Date().toISOString();

  if (!connected) {
    client.connect(target, { plaintext: true });
    connected = true;
  }

  let res = client.invoke('storage.ChatStorage/Connect', { name: user, at: now });
  check(res, { 'connect OK': (r) => r && r.status === grpc.StatusOK });

  res = client.invoke('storage.ChatStorage/Append', {
    sender: user,
    text: 'hello',
    sent_at: now,
  });
  check(res, { 'append OK': (r) => r && r.status === grpc.StatusOK });

  res = client.invoke('storage.ChatStorage/History', {});
  check(res, { 'history OK': (r) => r && r.status === grpc.StatusOK });

  client.invoke('storage.ChatStorage/Disconnect', { name: user, at: now });
}
