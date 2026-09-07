const CACHE_VERSION = 'v2.0.15';
const CACHE_NAME = `savvry-app-cache-${CACHE_VERSION}`;
const CACHED_EXTENSIONS = ['.html', '.wasm', '.png', '.ico', '.ttf', '.cvr', '.js', '.css'];

self.addEventListener('install', event => {
    self.skipWaiting();
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys().then(cacheNames => {
            return Promise.all(
                cacheNames.map(cacheName => {
                    if (cacheName.startsWith('savvry-app-cache-') && cacheName !== CACHE_NAME) {
                        return caches.delete(cacheName);
                    }
                })
            );
        })
    );
    self.clients.claim();
});

let versionCheckPromise = null;

function checkVersion() {
    if (!versionCheckPromise) {
        versionCheckPromise = fetch('/version')
            .then(response => {
                if (!response.ok) {
                    throw new Error('Version fetch failed');
                }
                return response.text();
            })
            .then(version => {
                if (version.trim() !== CACHE_VERSION) {
                    return caches.keys().then(cacheNames => {
                        return Promise.all(
                            cacheNames.map(cacheName => caches.delete(cacheName))
                        );
                    }).then(() => false);
                }
                return true;
            })
            .catch(() => true);
    }
    return versionCheckPromise;
}

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);

    const shouldCache = event.request.method === 'GET' &&
        (CACHED_EXTENSIONS.some(ext => url.pathname.endsWith(ext)) || url.pathname === '/');

    if (shouldCache) {
        event.respondWith(
            checkVersion().then(isVersionMatch => {
                if (!isVersionMatch) {
                    return fetch(event.request);
                }

                return caches.match(event.request).then(cachedResponse => {
                    if (cachedResponse) {
                        return cachedResponse;
                    }

                    return fetch(event.request).then(networkResponse => {
                        if (!networkResponse || networkResponse.status !== 200 || networkResponse.type !== 'basic') {
                            return networkResponse;
                        }

                        const responseToCache = networkResponse.clone();

                        caches.open(CACHE_NAME).then(cache => {
                            cache.put(event.request, responseToCache);
                        });

                        return networkResponse;
                    });
                });
            })
        );
    }
});