CREATE SCHEMA IF NOT EXISTS openbadges;

CREATE TABLE openbadges.scripts (
	filename VARCHAR(255) NOT NULL PRIMARY KEY,
	passed TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Clients OAuth 2.0 (client credentials) de l'API Open Badges ; id = client_id.
-- Seule l'empreinte SHA-256 du secret est conservée.
CREATE TABLE openbadges.client (
	id VARCHAR(64) NOT NULL PRIMARY KEY,
	name VARCHAR(255) NOT NULL,
	secret_hash VARCHAR(128) NOT NULL,
	scopes JSONB NOT NULL DEFAULT '[]',
	enabled BOOLEAN NOT NULL DEFAULT TRUE,
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW(),
	updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- L'émetteur (Profile) : qui atteste. Un établissement, une académie, l'ENT lui-même.
CREATE TABLE openbadges.issuer (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	name TEXT NOT NULL,
	url TEXT,
	email TEXT,
	description TEXT,
	image_url TEXT,
	-- Établissement (id Neo4j) au nom duquel l'émetteur atteste, ou NULL pour la plateforme.
	structure_id VARCHAR(36),
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX issuer_structure ON openbadges.issuer (structure_id);

-- Le badge (Achievement) : ce qui est attesté, indépendamment de qui l'obtient.
CREATE TABLE openbadges.achievement (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	issuer_id VARCHAR(36) NOT NULL REFERENCES openbadges.issuer(id) ON DELETE CASCADE,
	name TEXT NOT NULL,
	description TEXT NOT NULL,
	achievement_type VARCHAR(64) NOT NULL DEFAULT 'Badge',
	criteria_narrative TEXT,
	criteria_url TEXT,
	image_url TEXT,
	tags JSONB NOT NULL DEFAULT '[]',
	-- Alignements vers un référentiel de compétences : targetUrl peut désigner un CFItem servi
	-- par le module case, et l'attestation dit alors quelle compétence elle atteste.
	alignments JSONB NOT NULL DEFAULT '[]',
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX achievement_issuer ON openbadges.achievement (issuer_id);

-- L'attestation délivrée à une personne.
CREATE TABLE openbadges.credential (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	achievement_id VARCHAR(36) NOT NULL REFERENCES openbadges.achievement(id) ON DELETE CASCADE,
	subject_user_id VARCHAR(36) NOT NULL,
	name TEXT,
	narrative TEXT,
	-- Identité du porteur par EMPREINTE : une attestation circule hors de l'ENT, et la montrer
	-- ne doit pas livrer l'adresse de courriel de l'élève. Le sel voyage avec elle.
	identity_type VARCHAR(32) NOT NULL DEFAULT 'emailAddress',
	identity_hash VARCHAR(128),
	identity_salt VARCHAR(64),
	valid_from TIMESTAMP NOT NULL DEFAULT NOW(),
	valid_until TIMESTAMP,
	-- Le JWS compact signé, conservé tel quel : il est l'attestation. La resigner à la demande
	-- produirait une signature différente à chaque appel, et un porteur qui a archivé la
	-- première ne pourrait plus prouver qu'elles sont la même.
	jws TEXT NOT NULL,
	revoked BOOLEAN NOT NULL DEFAULT FALSE,
	revoked_reason TEXT,
	revoked_at TIMESTAMP,
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX credential_subject ON openbadges.credential (subject_user_id, created_at DESC);
CREATE INDEX credential_achievement ON openbadges.credential (achievement_id);
CREATE INDEX credential_revoked ON openbadges.credential (revoked) WHERE revoked;
