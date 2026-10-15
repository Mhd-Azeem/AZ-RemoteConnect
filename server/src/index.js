import { WebSocketServer } from 'ws';

// DEVELOPMENT ONLY: bound to loopback; no client identities or remote-control data.
// Never deploy publicly without authentication, authorization, rate limits and TLS.
const port = Number.parseInt(process.env.PORT ?? '8787', 10);
const host = '127.0.0.1';
const server = new WebSocketServer({ host, port, maxPayload: 4096 });
server.on('connection', (socket) => {
  socket.send(JSON.stringify({ type: 'server.hello', version: 1, status: 'development-only' }));
  socket.on('message', () => socket.close(1008, 'Signaling not implemented'));
});
console.log(`AZ RemoteConnect development placeholder: ws://${host}:${port}`);
