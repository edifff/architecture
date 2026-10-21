import http from 'k6/http';
import { check } from 'k6';

// Нагрузка на входной слой (Netty HTTP + Facade). При N репликах VU
// привязывается к своей реплике по имени контейнера — иначе SSE-подписка и
// HTTP-сессия одного пользователя окажутся в разных процессах.
export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 30),
      duration: __ENV.DURATION || '15s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const replicas = Number(__ENV.REPLICAS || 1);
  const base =
    replicas > 1
      ? `http://arch-labs-api-${1 + (__VU % replicas)}:8080`
      : 'http://api:8080';
  const user = `a${__VU}-${__ITER}-${Date.now()}`;
  const json = { headers: { 'Content-Type': 'application/json' } };

  let res = http.post(`${base}/sessions/${user}`);
  check(res, { 'connect 201': (r) => r.status === 201 });

  res = http.post(`${base}/messages/${user}`, JSON.stringify({ text: 'hello' }), json);
  check(res, { 'send 201': (r) => r.status === 201 });

  res = http.put(`${base}/notifications/${user}`, JSON.stringify({ enabled: false }), json);
  check(res, { 'notifications 204': (r) => r.status === 204 });

  res = http.get(`${base}/messages/${user}`);
  check(res, { 'history 200': (r) => r.status === 200 });

  res = http.del(`${base}/sessions/${user}`);
  check(res, { 'disconnect 204': (r) => r.status === 204 });
}
