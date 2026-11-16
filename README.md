# S3 Outposts Client

[![CI](https://github.com/cnrozkn/s3-outposts-client/actions/workflows/ci.yml/badge.svg)](https://github.com/cnrozkn/s3-outposts-client/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A web UI for **Amazon S3 on Outposts**. It is read-only by default, and upload, delete and rename can be turned
on per bucket. It is a web application, not an SDK: run the container next to your Outpost and open it in a
browser.

## Why

The AWS Management Console cannot browse objects on an Outpost, and most generic S3 browsers fail against it:

- **Addressing.** Outposts objects are reached only through an access point, by its ARN or its alias
  (`…--op-s3`). Requests go to an Outpost host (`<alias>.op-<outpost-id>.s3-outposts.<region>.amazonaws.com`)
  and are signed for `s3-outposts`, not `s3`. Tools that expect a plain bucket name and `s3` signing cannot talk
  to it. This app uses the AWS SDK for Java, which handles both; [a test](src/test/java/io/github/cnrozkn/s3client/OutpostsAddressingTest.java)
  checks the host and the signing scope offline.
- **Large folders.** On S3 on Outposts, listing with a delimiter has to walk every key under each common
  prefix, so a folder with millions of objects can take minutes. This app lists without a delimiter: it reads
  keys in order and, when a key opens a subfolder, records the folder once and jumps past it with `StartAfter`.

## Features

- Browse buckets and folders, search by prefix, see object details, metadata, versions and delete markers
- Download, with HTTP range support so interrupted downloads resume and large PDFs and videos open part by part
- Preview images, PDFs and text (sandboxed; SVG, HTML, JSON and XML are shown as plain text)
- Optional per bucket: upload, replace (upload over an existing key), create folder, rename or move, delete
- Stays responsive on large buckets: request and time budget per page, a short listing cache, folder jumps
- Form login, CSRF protection, an audit log of every write, Prometheus metrics and health probes
- One container for `linux/amd64` and `linux/arm64` (1U Outposts servers run on Graviton)

## Quick start

The machine that runs the container must reach your S3 on Outposts endpoint: inside the Outpost VPC, or on
premises through an endpoint that uses a customer-owned IP pool.

```bash
docker run --rm -p 8080:8080 \
  -e S3CLIENT_REGION=<region> \
  -e S3CLIENT_USERNAME=admin \
  -e S3CLIENT_PASSWORD='<long random password>' \
  -e S3CLIENT_BUCKETS_0_NAME=logs \
  -e S3CLIENT_BUCKETS_0_ALIAS='<access point alias ending in --op-s3>' \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
  ghcr.io/cnrozkn/s3-outposts-client:latest
```

Then open <http://localhost:8080>.

## Configuration

Every setting is an environment variable or a key under `s3-client` in a YAML file.

| Variable | Default | Meaning |
| --- | --- | --- |
| `S3CLIENT_USERNAME`, `S3CLIENT_PASSWORD` | required | The login. The app does not start without them. |
| `S3CLIENT_BUCKETS_<n>_NAME` | required | Short name used in URLs: letters, digits, `.`, `_`, `-`. |
| `S3CLIENT_BUCKETS_<n>_ALIAS` | required | Access point alias (`…--op-s3`) or access point ARN. |
| `S3CLIENT_BUCKETS_<n>_CAPABILITIES` | `LIST,DETAIL,DOWNLOAD,PREVIEW,VERSIONS` | Allowed operations, comma-separated. Add `UPLOAD` (upload, replace, create folder) and `DELETE` to allow writes. Rename needs both. |
| `S3CLIENT_REGION` | SDK default (`AWS_REGION`) | Region the Outpost is homed to. |
| `S3CLIENT_ENDPOINT` | derived from the alias | Only for a custom endpoint, for example `https://s3-outposts.<region>.amazonaws.com`. |
| `S3CLIENT_PAGE_SIZE` | `100` | Entries per page, 1 to 1000. |
| `S3CLIENT_PREVIEW_MAX_SIZE` | `5MB` | Largest object the preview opens. |
| `S3CLIENT_UPLOAD_MAX_SIZE` | `5GB` | Largest upload; 5 GB is the single PUT limit. |
| `S3CLIENT_WRITE_TIMEOUT` | `30m` | Time limit for one upload, copy or delete call. |
| `S3CLIENT_LIST_CACHE_TTL` | `30s` | How long a listing page is reused. `0` turns the cache off. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | plain text | `ecs` or `logstash` for JSON logs. |
| `TZ` | `UTC` in the container | Time zone of the timestamps in the UI. |

With many buckets a YAML file is easier. Mount it and point Spring Boot at it with
`-e SPRING_CONFIG_IMPORT=file:/config/application.yml`:

```yaml
s3-client:
  region: <region>
  buckets:
    - name: logs
      alias: <access point alias>
    - name: uploads
      alias: <access point alias>
      capabilities: [LIST, DETAIL, DOWNLOAD, PREVIEW, VERSIONS, UPLOAD, DELETE]
```

### Credentials

The app uses the AWS SDK default credential chain: environment variables, a shared profile, an EC2 instance
profile, an ECS task role or EKS Pod Identity. IAM roles for service accounts (IRSA) need the
`software.amazon.awssdk:sts` module, which is not bundled.

### IAM permissions

Grant these to the identity the app runs as. The access point policy and the bucket policy must allow the same
requests.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Read",
      "Effect": "Allow",
      "Action": [
        "s3-outposts:ListBucket",
        "s3-outposts:ListBucketVersions",
        "s3-outposts:GetObject",
        "s3-outposts:GetObjectVersion"
      ],
      "Resource": [
        "arn:aws:s3-outposts:<region>:<account-id>:outpost/<outpost-id>/accesspoint/<access-point-name>",
        "arn:aws:s3-outposts:<region>:<account-id>:outpost/<outpost-id>/accesspoint/<access-point-name>/object/*"
      ]
    },
    {
      "Sid": "WriteOnlyWhenUploadOrDeleteIsTurnedOn",
      "Effect": "Allow",
      "Action": [
        "s3-outposts:PutObject",
        "s3-outposts:DeleteObject",
        "s3-outposts:GetObjectTagging",
        "s3-outposts:PutObjectTagging"
      ],
      "Resource": "arn:aws:s3-outposts:<region>:<account-id>:outpost/<outpost-id>/accesspoint/<access-point-name>/object/*"
    }
  ]
}
```

Rename copies the object's tags, which is why the write statement includes the tagging actions.

## Performance and limits

Listing is built for folders with millions of objects:

- **Folder jumps.** Keys are read in order and each subfolder costs one request, however large it is. After a jump
  the next request asks for 100 keys instead of 1000, since a large folder is often followed by another one.
- **Folders first.** The first page puts the folders of the first 2,000 keys on top. Folders further down appear
  in key order on later pages.
- **A budget per page.** A page makes at most 10 S3 requests within 3 seconds. When the budget runs out, the page
  shows what it has found, says that listing paused, and Next continues from there.
- **Listing cache.** Each page is kept for 30 seconds, so Previous, Next and reopened folders do not hit S3 again.
  A write drops the cached pages of its bucket. The cache is per replica: with several replicas, the others can
  show an older page until it expires.

Some things are left out on purpose. Object counts, folder sizes, sorting by size or date and substring search
all need a scan of the whole bucket, and S3 Inventory is not available on Outposts. Search is by prefix, and the
order is S3's key order.

S3 on Outposts has no CloudWatch request metrics, so the app publishes its own on the management port
(`:8081/actuator/prometheus`):

| Metric | What it shows |
| --- | --- |
| `s3_client_calls_seconds{operation, outcome}` | Every S3 request, with its duration and result |
| `s3_client_listing_requests`, `s3_client_listing_keys` | Requests and keys one listing page needed |
| `cache_gets_total{cache="s3client.list"}` | Listing cache hits and misses |
| `http_server_requests_seconds` | Page response times |

To see how much a delimiter costs on your Outpost, time both forms of the same listing:

```bash
time aws s3api list-objects-v2 --bucket '<alias>' --prefix '<large-folder>/' --delimiter / --max-keys 1000 --no-paginate > /dev/null
```

```bash
time aws s3api list-objects-v2 --bucket '<alias>' --prefix '<large-folder>/' --max-keys 1000 --no-paginate > /dev/null
```

## Writes

Writes are off unless a bucket lists `UPLOAD` or `DELETE`. Each one needs a CSRF token and is logged on the
`s3client.audit` logger as `action=… user=… bucket=… key=…`.

- **Upload and replace.** The browser sends the file as the raw request body, and the app streams it into
  PutObject, so the file is never written to disk. The limit is 5 GB per file, the single PUT limit. Before
  replacing an existing key the UI asks; with versioning on, the older versions stay.
- **Create folder** writes an empty object named `<folder>/`, the same way the S3 console does.
- **Rename or move** is a server-side CopyObject followed by DeleteObject. It is not atomic: if the copy fails
  the source stays, and if the delete fails both keys exist. Objects over 5 GB cannot be renamed.
- **Delete** removes one object. With versioning on, S3 adds a delete marker and keeps the older versions.

A long upload also needs every proxy and load balancer in front of the app to allow it: an idle timeout of
60 seconds cuts large uploads.

## Security notes

- There is one login for everyone. Run the app behind your VPN or SSO proxy, and do not expose it to the
  internet.
- Port 8081 serves health and metrics without a login. Keep it on the internal network.
- Turn on writes only for the buckets that need them, and keep the IAM policy as narrow as the capabilities.
- Previews are sent with `Content-Security-Policy: sandbox`, and markup types are shown as plain text, so a
  stored page cannot run script in your session.
- To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Build from source

Java 21 is the only requirement; the Maven wrapper downloads Maven.

```bash
./mvnw verify
```

```bash
./mvnw spotless:apply
```

```bash
S3CLIENT_USERNAME=admin S3CLIENT_PASSWORD=change-me S3CLIENT_REGION=<region> \
  S3CLIENT_BUCKETS_0_NAME=logs S3CLIENT_BUCKETS_0_ALIAS='<access point alias>' \
  ./mvnw spring-boot:run
```

```bash
docker build -t s3-outposts-client .
```

`verify` runs the tests and the formatting check; `spotless:apply` fixes the formatting. Pushing a tag such as
`v0.1.0` makes GitHub Actions test the code and publish the image to GHCR for both architectures.

## License

Copyright 2026 Caner Özkan. Licensed under the [Apache License, Version 2.0](LICENSE).
