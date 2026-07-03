# Phase 2: Database & OSS Integration (Backend)
*Focuses on connecting the server to ArangoDB, writing repository CRUD operations, configuring Alibaba Cloud OSS, and implementing secure client-direct upload flows.*

## Tasks
- [x] **ArangoDB Integration**
  - [x] Add the ArangoDB Java driver dependency to :server/build.gradle.kts.
  - [x] Create database connection configurations (host, port, user, pass, DB name).
  - [x] Create an initialization script that verifies the database exists, creating collections (films, assets, tracks, clips, jobs) if they are missing.
  - [x] Implement database Repository helper classes:
    - [x] FilmRepository: Insert, get by ID, update, delete, list all.
    - [x] AssetRepository: Insert, find by ID, query by filmId (or null for global).
- [x] **Alibaba Cloud OSS Service**
  - [x] Add Alibaba Cloud OSS SDK dependency to :server/build.gradle.kts.
  - [x] Write OssService referencing endpoint, bucket name, accessKeyId, and accessKeySecret.
  - [x] Implement generatePreSignedUploadUrl(objectKey: String) generating secure client-direct PUT/POST URLs.
- [x] **Core REST Endpoints (:server)**
  - [x] **Film Endpoints (/api/films)**:
    - [x] GET / - Retrieve all films.
    - [x] GET /{id} - Retrieve a single film.
    - [x] POST / - Create a new film.
    - [x] DELETE /{id} - Delete a film.
  - [x] **Asset Endpoints (/api/assets)**:
    - [x] GET / - Fetch assets matching a filmId query param or global assets.
    - [x] POST /upload-url - Request a pre-signed OSS upload URL for direct-to-cloud asset uploads.
    - [x] POST / - Save uploaded asset metadata to ArangoDB once upload completes.
