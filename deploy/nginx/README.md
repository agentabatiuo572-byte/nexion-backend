# TEST ingress performance

`uvel-test-performance.conf` is an HTTP-context include for the existing TEST
Nginx 1.28 deployment. It enables HTTP/2 and compression of JavaScript, JSON, CSS
and SVG without changing upstreams, authentication, TLS certificates or request limits.

Install as `/etc/nginx/conf.d/uvel-test-performance.conf`, validate with
`sudo nginx -t`, then reload Nginx. The guarded release broker's managed site
file remains unchanged. To roll back, remove only this task's include, validate
and reload. Do not enable the release timer or modify other components for this change.

2026-10-29 TEST activation passed `nginx -t`; Chrome negotiated `h2`.
This transport check alone does not establish the M1 300ms/1s performance targets.
