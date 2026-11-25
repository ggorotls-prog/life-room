/* 건강 기록 사이트 알림(2026-11-25 룡 「아이폰도 동일하게」). 서버(life-push)가 보낸 알림을 띄우고, 누르면 그 화면을 연다.
   화면 파일은 저장해 두지 않는다(늘 새 판을 받는다) */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (x) { d = { title: e.data ? e.data.text() : '건강 기록' }; }
  e.waitUntil(self.registration.showNotification(d.title || '건강 기록', {
    body: d.body || '', tag: d.tag || undefined, icon: 'icon-192.png', data: { url: d.url || './' }
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = new URL((e.notification.data && e.notification.data.url) || './', self.registration.scope).href;
  e.waitUntil((async () => {
    const list = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of list) {
      if (c.url.startsWith(self.registration.scope)) { await c.focus(); c.postMessage({ open: url }); return; }
    }
    await self.clients.openWindow(url);
  })());
});
