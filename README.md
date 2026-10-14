# Bhumisaara Backend

The REST API for Bhumisaara, a fertilizer distribution and subsidy management platform for Sri Lanka. It manages user accounts, service areas, fertilizer applications, physical stock records, marketplace orders, subsidy credits, and administrative audit history.

This repository contains the Spring Boot application and PostgreSQL persistence layer. The separate Next.js frontend handles wallet signatures and blockchain interaction; the backend records the submitted transaction hashes and enforces application business rules.

## Technology

| Area | Tools |
| --- | --- |
| Runtime | Java 17 |
| Framework | Spring Boot 4.1.0, Spring MVC |
| Persistence | PostgreSQL, Spring Data JPA, Hibernate |
| Authentication | Spring Security, JJWT 0.11.5, BCrypt |
| Validation | Jakarta Bean Validation |
| Build | Maven wrapper, Lombok |
| Configuration | Spring profiles, environment file imports, spring-dotenv |

## Domain modules

| Module | Responsibilities |
| --- | --- |
| Accounts and authentication | Registration, login, tokens, profiles, wallet links, bans |
| Service areas | Area management, farmer membership, officer assignments |
| Fertilizer requests | Farmer applications, officer review, approved and collected quantities |
| Stock distribution | Import batches, sacks, custody transfers, handovers, disputes |
| Subsidy credits | Seasonal issuance, ledger balances, reconciliation |
| Marketplace | Seller listings, order quotes, stock reservations, confirmations, disputes |
| Redemption | Seller claims, government review, recording settlement burns |
| Administration | User and role management, area coverage, health summaries, append-only audit logs |

## Local setup

### Requirements

- JDK 17 or newer.
- A running PostgreSQL instance and credentials allowed to create/update application tables.
- An empty database named `bhumisaara`, or another database configured in `DB_URL`.
- Internet access on the first build so the included Maven wrapper can download dependencies.

Run commands from this repository's root. A separate Maven installation is not required.

### 1. Configure the environment

```bash
cp Example.env .env.development
```

Edit `.env.development` with your local settings:

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/bhumisaara
DB_USERNAME=postgres
DB_PASSWORD=your_local_postgres_password
JWT_SECRET=replace_with_a_base64_encoded_secret
JWT_EXPIRATION=86400000
JWT_REFRESH_EXPIRATION=604800000
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

| Variable | Purpose |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC connection URL. |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials. |
| `JWT_SECRET` | Base64-encoded signing key containing at least 256 bits. |
| `JWT_EXPIRATION` | Access token lifetime in milliseconds. |
| `JWT_REFRESH_EXPIRATION` | Refresh token lifetime in milliseconds. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated frontend origins allowed to call the API. |

Generate a signing key and paste the output into `JWT_SECRET`:

```bash
openssl rand -base64 64
```

The committed [Example.env](Example.env) contains placeholders. Environment files matching `.env*` are ignored by Git.

### 2. Start the API

```bash
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`. If the Unix wrapper is not executable, run `chmod +x mvnw` first.

The default base URL is `http://localhost:8080/api/v1`. The `development` profile is active by default and Hibernate updates the database schema on startup.

### 3. Use the demo accounts

On non-production startup, [DataSeeder.java](src/main/java/com/bandits/bhumisaara/config/DataSeeder.java) creates missing role records, a Kandy service area, and three privileged demo accounts:

| Role | Email | Demo password |
| --- | --- | --- |
| `SYSTEM_ADMIN` | `sysadmin@bhumisaara.lk` | `Password123!` |
| `GOVERNMENT_ADMIN` | `govadmin@bhumisaara.lk` | `Password123!` |
| `AGRARIAN_SERVICE_OFFICER` | `officer@bhumisaara.lk` | `Password123!` |

The seeded officer is assigned to Kandy. Existing accounts are not reset on restart. Farmers, private agro-dealers, and organic fertilizer producers register through the application or `/auth/register`.

The demo seeder is disabled under the `production` profile. Production roles and initial privileged accounts must be provisioned separately.

## Commands and profiles

| Command | Purpose |
| --- | --- |
| `./mvnw spring-boot:run` | Run the API with the default development profile. |
| `./mvnw compile` | Compile application sources. |
| `./mvnw test` | Run the existing tests. |
| `./mvnw package` | Test and build the executable application JAR. |
| `./mvnw spring-boot:run -Dspring-boot.run.profiles=staging` | Run with staging configuration. |

| Profile | Environment file | Schema handling | Demo seeding |
| --- | --- | --- | --- |
| `development` | `.env.development` | Hibernate `update` | Enabled |
| `staging` | `.env.staging` | Hibernate `update` | Enabled |
| `production` | `.env.production` | Hibernate `none` | Disabled |

Production requires an existing database schema. After packaging, a production launch can use:

```bash
java -jar target/bhumisaara-0.0.1-SNAPSHOT.jar --spring.profiles.active=production
```

Run from the repository root when relying on the relative `.env.production` import. Configure an explicit frontend origin and deployment-specific credentials.

The existing test suite contains a Spring context-loading test. It uses the application's configuration and requires a reachable database; it does not provide comprehensive business-rule coverage.

## API and authentication

All endpoint paths below are relative to `/api/v1`. The prefix is configured once as the servlet context path; controllers map plain paths such as `/batches`.

| Path group | Purpose |
| --- | --- |
| `/auth` | Registration, login, token refresh, validation, and current-user information |
| `/users` | The signed-in user's profile and wallet link |
| `/farmers` | Farmer service-area membership |
| `/areas`, `/officers` | Area lookup, officer information, and assignment |
| `/fertilizer-requests` | Applications, personal/area history, and officer review |
| `/batches`, `/sacks` | Import records, sack labels, and custody information |
| `/transfers` | Government-to-officer stock movements and area demand |
| `/distributions` | Officer-to-farmer handovers and disputes |
| `/credits` | Seasonal issuance, balances, and reconciliation |
| `/listings`, `/orders` | Marketplace inventory, quotes, and order lifecycle |
| `/redemption-claims` | Claims, review, and settlement |
| `/admin` | Platform administration and audit history |

Log in to obtain a token:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"sysadmin@bhumisaara.lk","password":"Password123!"}'
```

Use the returned `token` as a bearer token for protected endpoints:

```bash
curl http://localhost:8080/api/v1/users/me/profile \
  -H 'Authorization: Bearer <token>'
```

Controller annotations enforce role access. Services derive the acting user from the authenticated principal and check ownership or area membership where required. Self-registration accepts only `FARMER`, `PRIVATE_AGRO_DEALER`, and `ORGANIC_FERTILIZER_PRODUCER`; privileged roles are assigned by a system administrator.

Passwords are BCrypt-hashed. The JWT filter rechecks account bans on each authenticated request. API errors use a shared response shape with `status`, `error`, and `message` fields.

## Business rules

### Physical stock

- Recording a batch also generates its sacks, normally 50 kg each, with a smaller final sack when needed.
- Sacks progress through `AT_CENTRAL`, `WITH_OFFICER`, and `DELIVERED`.
- Officer transfers change custody without reducing a batch's recorded volume.
- At farmer collection, the frontend burns stock tokens from the officer's wallet. Recording the handover consumes the sacks, deducts batch volume, and advances the request's collected quantity.
- The collection limit is the request's approved amount minus prior collections. The current handover flow does not depend on the legacy `user_quotas` table.
- Unique sack-consumption records and transaction hashes help reject duplicate submissions. Disputes flag a handover without reversing it.

### Subsidy credits and marketplace

- Stock tokens and subsidy credits are separate asset types, even though the frontend uses one ERC-1155 contract.
- Each season shares one credit token ID, and each farmer can have one recorded issuance per season.
- One credit covers 1 kg chemical or 1.5 kg organic fertilizer. [CreditMath.java](src/main/java/com/bandits/bhumisaara/service/CreditMath.java) calculates conversion using integer arithmetic.
- The seller's role determines whether a listing is organic. Order totals are calculated by the server.
- Placing an order reserves stock. Sellers mark goods ready; farmers confirm collection and supply the credit transfer hash. Cash-only confirmations have no transfer hash.
- Sellers submit claims against credits earned from completed orders. Government approval precedes the seller's burn; recording that burn moves an approved claim to `PAID`.
- Cash amounts and settlement states are recorded; the backend has no payment processor or bank payout integration.

### Platform administration

System administrators manage access and platform configuration. They do not mint stock, issue credits, or approve redemption claims. Administrative mutations write audit records in the same database transaction. Audit history has no update or delete endpoint, and service areas are deactivated rather than deleted.

## Blockchain integration boundary

There is no backend blockchain client. Wallet signatures, token transfers, mints, burns, receipt waits, and on-chain balance reads happen in the frontend. The API records client-submitted hashes and checks database rules, but does not independently verify blockchain receipts.

A confirmed blockchain transaction and a database write are separate operations. Failed recording calls require reconciliation using the confirmed hash. Credit balance endpoints report the database ledger, which can differ from wallet holdings.

Wallet linking currently accepts an address from an authenticated client and enforces address uniqueness; it does not verify a wallet ownership signature.

## Project structure

```text
src/main/java/com/bandits/bhumisaara/
  BhumisaaraApplication.java # Application entry point
  config/                   # Non-production data seeder
  controller/               # HTTP endpoints and role authorization
  dto/request/              # Validated input payloads
  dto/response/             # Response payloads
  entity/                   # JPA database models
  enums/                    # Roles, statuses, and token types
  exception/                # Shared error handling
  repository/               # Persistence queries
  security/                 # JWT handling and current-user resolution
  service/                  # Business rules and transactions
src/main/resources/         # Base and profile-specific configuration
src/test/                   # Spring context-loading test
```

Write operations belong in transactional services; controllers validate input and delegate. Keep blockchain token IDs as strings in backend models and DTOs. See [AGENTS.md](AGENTS.md) for repository conventions and detailed domain documentation; current source code takes precedence over stale examples in that guide.

### Chain verification and wallet proof

Set `CHAIN_RPC_URL`, `CHAIN_CONTRACT_ADDRESS`, and `CHAIN_ID` (80002 for Polygon Amoy) on the backend; see `.env.example`. The RPC must serve `eth_chainId`, `eth_getTransactionByHash`, `eth_getTransactionReceipt`, `eth_getCode`, and `eth_call` (including contract-creation simulation for undeployed ERC-6492 smart wallets). The backend contract address must match the frontend's `NEXT_PUBLIC_CONTRACT_ADDRESS`. All six ledger writes now verify the confirmed ERC-1155 `TransferSingle` movement and reserve its hash in the database. The reservation and ledger write share one database transaction, so a failed write can be retried with the same confirmed hash.

Wallet linking uses `POST /users/me/wallet/challenge?address=...`, then a signed message submitted to `PATCH /users/me/wallet` as `walletAddress`, `challengeId`, and `signature`. Challenges expire after five minutes and are single use. EOA, deployed ERC-1271, and ERC-6492 smart wallet signatures are accepted. Session versions invalidate access and refresh tokens after password resets or wallet clearing. Existing sessions use the current database role for authorization.
