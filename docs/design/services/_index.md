# Service design

Status: **planned** - a service's spec is written when that service is built (the first ones, #6 and #7). Until then this folder holds only this index.

This folder holds **one spec per microservice** (the Service Spec Lifecycle): each service's responsibility, its REST endpoints (the OpenAPI contract), its Elasticsearch index/mappings, and the mesh envelopes it publishes or consumes. A spec is written before the service is built and kept current as it changes; this `_index.md` will become the per-service index.
