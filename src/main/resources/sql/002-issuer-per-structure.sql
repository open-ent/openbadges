-- Un émetteur par établissement.
--
-- Jusqu'ici l'attestation héritait de l'émetteur du BADGE : un badge défini une fois ne pouvait
-- être délivré qu'au nom d'un seul émetteur. Avec un émetteur par établissement, cela
-- obligerait à redéfinir chaque badge pour chacun des 360 établissements.
--
-- La norme distingue pourtant les deux : un Achievement a un créateur, une AchievementCredential
-- a un issuer. Le badge peut donc être défini une fois par la plateforme et décerné par
-- l'établissement. C'est ce que cette migration installe.

-- Un seul émetteur par établissement, garanti par la base et non par la discipline.
CREATE UNIQUE INDEX IF NOT EXISTS issuer_structure_unique
	ON openbadges.issuer (structure_id) WHERE structure_id IS NOT NULL;

-- L'émetteur de l'attestation, distinct de celui du badge.
ALTER TABLE openbadges.credential
	ADD COLUMN IF NOT EXISTS issuer_id VARCHAR(36) REFERENCES openbadges.issuer(id);

-- Les attestations déjà délivrées gardent l'émetteur sous lequel elles ont été signées : leur
-- JWS le porte, et le changer rendrait la signature incohérente avec son contenu.
UPDATE openbadges.credential c
	SET issuer_id = a.issuer_id
	FROM openbadges.achievement a
	WHERE a.id = c.achievement_id AND c.issuer_id IS NULL;

ALTER TABLE openbadges.credential ALTER COLUMN issuer_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS credential_issuer ON openbadges.credential (issuer_id);

-- Le badge n'impose plus son émetteur : il en garde un comme propriétaire, mais l'attestation
-- ne le reprend plus.
COMMENT ON COLUMN openbadges.achievement.issuer_id IS
	'Propriétaire du badge (celui qui l''a défini). L''émetteur d''une attestation est porté par openbadges.credential.issuer_id.';
