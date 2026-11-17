# Contributing

Bug reports and pull requests are welcome.

- For a larger change, open an issue first, so the approach is agreed before you write the code.
- `./mvnw verify` must pass. It runs the tests and checks the formatting; `./mvnw spotless:apply` fixes the
  formatting.
- Tests run offline, without an Outpost. Never put real access point aliases, ARNs, account IDs or credentials in
  code, tests, issues or pull requests.
- The app stays read-only by default: a new write operation must be covered by a capability that is off unless
  a bucket lists it.
- To report a security problem, follow [SECURITY.md](SECURITY.md) instead of opening an issue.

Contributions are licensed under the [Apache License, Version 2.0](LICENSE), like the rest of the project.
