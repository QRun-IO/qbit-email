# Test-only SNS certificate and private key

These are public test fixtures, never production credentials. Generated at authoring
time with OpenSSL; tests do not spawn OpenSSL or write certificate files.

```
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 36500 \
  -subj "/CN=TEST ONLY - SNS webhook verification fixture" \
  -keyout test-only-private-key.pem -out test-only-certificate.pem
```

The fake EmailHttp serves this certificate for an AWS-shaped test URL. SNS tests
sign with this key in memory. Never trust this certificate outside the tests.
