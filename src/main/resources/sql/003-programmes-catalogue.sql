-- Catalogue de badges rangé sur les programmes officiels (référentiel CASE du module case).
--
-- Un badge du catalogue est défini une fois par la plateforme et décerné par chaque établissement
-- en son nom. Il désigne l'énoncé du référentiel qu'il atteste (un sous-domaine, par cycle) et
-- porte la liste des énoncés qu'il couvre : un exercice aligné sur l'un d'eux est une preuve
-- possible, que l'enseignant voit avant de décerner.

ALTER TABLE openbadges.achievement
	ADD COLUMN IF NOT EXISTS catalogue VARCHAR(255),
	ADD COLUMN IF NOT EXISTS case_item VARCHAR(36),
	ADD COLUMN IF NOT EXISTS cycle VARCHAR(8),
	ADD COLUMN IF NOT EXISTS subject TEXT,
	ADD COLUMN IF NOT EXISTS levels JSONB NOT NULL DEFAULT '[]',
	ADD COLUMN IF NOT EXISTS objectives JSONB NOT NULL DEFAULT '[]';

CREATE UNIQUE INDEX IF NOT EXISTS achievement_case_item
	ON openbadges.achievement (case_item) WHERE case_item IS NOT NULL;
CREATE INDEX IF NOT EXISTS achievement_catalogue ON openbadges.achievement (catalogue);

-- Empreinte de chaque catalogue livré avec le module : il n'est réécrit que s'il a changé.
CREATE TABLE IF NOT EXISTS openbadges.bundle (
	resource VARCHAR(255) NOT NULL PRIMARY KEY,
	sha256 VARCHAR(64) NOT NULL,
	loaded_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Badges détenus par un élève : lus à chaque proposition de badges d'une classe.
CREATE INDEX IF NOT EXISTS credential_subject_achievement
	ON openbadges.credential (subject_user_id, achievement_id);
