import grpc from 'k6/net/grpc';
import { check } from 'k6';

// Нагрузка на доменный слой. При N репликах каждый VU привязывается к своей
// реплике по имени контейнера — это клиентская session affinity: Connect и
// последующие вызовы одного пользователя обязаны попасть в тот же процесс.
// Канал gRPC переиспользуется на протяжении жизни VU (как у реального клиента).
const client = new grpc.Client();
client.load(['/proto'], 'chat.proto');
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
      ? `arch-labs-chat-${1 + (__VU % replicas)}:50052`
      : 'chat:50052';
  const user = `u${__VU}-${__ITER}-${Date.now()}`;

  if (!connected) {
    client.connect(target, { plaintext: true });
    connected = true;
  }

  let res = client.invoke('chat.ChatService/Connect', { name: user });
  check(res, { 'connect OK': (r) => r && r.status === grpc.StatusOK });

  res = client.invoke('chat.ChatService/Send', { name: user, text: 'hello' });
  check(res, { 'send OK': (r) => r && r.status === grpc.StatusOK });

  res = client.invoke('chat.ChatService/SetNotificationsEnabled', {
    name: user,
    enabled: false,
  });
  check(res, { 'notifications OK': (r) => r && r.status === grpc.StatusOK });

  res = client.invoke('chat.ChatService/History', { name: user });
  check(res, { 'history OK': (r) => r && r.status === grpc.StatusOK });

  client.invoke('chat.ChatService/Disconnect', { name: user });
}
