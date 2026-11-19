# Licensing

r7 is licensed under the **Business Source License (BSL) 1.1**. Each release converts to the **Apache License 2.0** three years after it is published.

This page is a summary. The binding terms are in [LICENSE](https://github.com/ethlo/r7/blob/main/LICENSE).

## Free for production use

Anyone can use r7 in production, at any company size: run it in front of your own applications and services, modify it, and build your own filters and predicates on it. That includes services you provide to your customers, such as a SaaS product whose traffic passes through r7.

## Competing offerings

The one thing the licence does not allow is using r7 in a **Competing Offering**: a product or service offered to third parties whose main value is gateway, reverse-proxy or traffic-journaling functionality like r7's. That covers:

* a hosted or managed service that lets third parties route or record their own traffic through r7
* distributing r7, or a fork of it, as a gateway product

If you want to do either, [contact the licensor](https://r7.ethlo.com) about a commercial licence.

## Extension API

The `r7-api` module, which holds the interfaces that filters, predicates and other extensions are written against, is licensed under the **Apache License 2.0** (see [r7-api/LICENSE](https://github.com/ethlo/r7/blob/main/r7-api/LICENSE)). Extensions you write against it are yours to license as you like.

## Open source after three years

Each release of r7 becomes available under the Apache License 2.0 three years after its publication date.
