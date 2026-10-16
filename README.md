# Open ENT — module `openbadges` (Open Badges 3.0)

Module Vert.x qui fait d'Open ENT un **émetteur d'attestations vérifiables** au format
[1EdTech Open Badges 3.0](https://www.1edtech.org/standards/open-badges) : des *Verifiable
Credentials* du W3C, signées, et contrôlables **hors de la plateforme**.

| Spécification | Support |
|---|---|
| `AchievementCredential` (VCDM 2.0 + contexte OB 3.0) | ✅ émission, signature, service |
| Preuve **VC-JWT** (RS256), JWKS publié | ✅ |
| Preuve *Data Integrity* (`eddsa-rdfc-2022`) | ❌ voir ci-dessous |
| `Achievement`, `Profile`, `AchievementSubject`, `Alignment` | ✅ |
| Révocation (`1EdTechRevocationList`) | ✅ liste servie publiquement |
| API : `getServiceDescription`, `getCredentials`, `getProfile` | ✅ OAuth 2.0 client credentials |
| API : `upsertCredential`, `putProfile` | ❌ Open ENT émet, il n'héberge pas |

Contexte et feuille de route : [`docs/international/interoperabilite-1edtech.md`](../../docs/international/interoperabilite-1edtech.md).

## Les deux propriétés qui commandent tout

**Une attestation circule hors de l'ENT.** Déposée dans un portefeuille, jointe à une
candidature, elle est lue par des tiers qui n'ont aucun compte ici. D'où deux conséquences
inscrites dans le code :

- les routes publiques — JWKS, badge, émetteur, liste de révocation, attestation — ne portent
  **aucun `@SecuredAction`**. Si elles exigeaient une session, l'attestation serait
  invérifiable, c'est-à-dire sans valeur ;
- l'identité du porteur n'y figure que par **empreinte** (`sha256$…` de l'adresse *suivie du
  sel*, le sel voyageant avec l'attestation). Montrer une attestation ne doit pas livrer
  l'adresse de l'élève. L'identifiant du sujet est une `urn:uuid:` et non une URL : une adresse
  de la forme `/subjects/<id>` apprendrait à tout lecteur que cette personne a un compte ici.

**Elle est vérifiée longtemps après.** Toutes les URI qu'elle porte doivent continuer de
répondre : celle de l'attestation, du badge, de l'émetteur, de la liste de révocation et du
JWKS. Aucune n'est décorative, et **la clé ne se remplace pas à la légère** — changer le `kid`
sans publier l'ancienne clé rend invérifiables toutes les attestations déjà délivrées.

## Pourquoi VC-JWT et pas Data Integrity

La norme admet les deux. La preuve *Data Integrity* (`eddsa-rdfc-2022`) exige une
**canonicalisation RDF** du document JSON-LD avant signature — une dépendance lourde que le JDK
seul ne fournit pas, et que les autres modules de l'ENT n'ont pas. VC-JWT ne demande qu'un JWS
RS256 : c'est la même mécanique que `lti` et `oneroster`, elle tient avec le JDK, et les
portefeuilles la lisent. Le coût est réel et assumé : un vérificateur qui n'accepterait *que*
Data Integrity ne saura pas lire nos attestations.

## Architecture

```
fr.openent.openbadges
├── OpenBadges.java          BaseServer : schéma SQL, contrôleurs
├── OpenBadgesContext.java   configuration et dépendances partagées
├── core/                    URI publiques, erreurs
├── badge/
│   ├── Credentials.java     construction des objets OB 3.0, empreintes (pure, testée)
│   └── RevocationList.java  la liste que consulte le vérificateur
├── security/                JWT RS256, JWKS, jetons d'accès, secrets (JDK uniquement)
├── api/Http.java            réponses, pagination, erreurs 1EdTech
├── service/                 PostgreSQL (schéma openbadges)
└── controller/
    ├── TokenController      /openbadges/oauth/token (client credentials)
    ├── ObController         /ims/ob/v3p0 — discovery, credentials, profile
    ├── PublicController     /openbadges — JWKS, attestation, badge, émetteur, révocations
    └── AdminController      /openbadges/admin — émetteurs, badges, clients, délivrance
```

## Droits

Deux niveaux, et la distinction est volontaire.

- **Configurer** — créer un émetteur, définir un badge, ouvrir un client OAuth — relève du
  **super administrateur**.
- **Délivrer et retirer** relève du droit de workflow **`openbadges.issue`**, attribuable par
  profil dans la gestion des accès : c'est un enseignant qui sait qu'un élève a mérité son
  badge, pas l'administrateur de la plateforme.

## Une limite à connaître avant d'ouvrir un client

La norme prévoit que l'accès aux attestations d'une personne passe par un `authorization_code`
OAuth, c'est-à-dire par **le consentement explicite de cette personne**. Ce module n'implémente
que `client_credentials` : un client autorisé lit les attestations d'un porteur qu'il désigne
par le paramètre `subject`. C'est suffisant pour un service de l'établissement, **insuffisant
pour un portefeuille tiers**, et `/discovery` le déclare plutôt que de le laisser découvrir.

## Alignement sur un référentiel de compétences

Un badge porte des `alignment[]` dont le `targetUrl` peut désigner un `CFItem` servi par le
module [`case`](../case). L'attestation dit alors exactement quelle compétence du socle elle
atteste, et un tiers peut aller la lire : les deux normes se rejoignent là.

## Déploiement

Port **8173**, schéma SQL **`openbadges`**, routes `/openbadges` et `/ims/ob`.

La clé privée de signature vient de la configuration (`openbadges.private-key`, PKCS#8 PEM),
elle-même alimentée par un Secret Kubernetes :

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out openbadges-private.pem
helm upgrade … --set-file secrets.OPENBADGES_PRIVATE_KEY=openbadges-private.pem
```

**Sans clé configurée, une clé éphémère est générée à chaque démarrage.** Les attestations
délivrées entre deux redémarrages deviennent alors définitivement invérifiables : réservé au
développement.

## Tests

```bash
mvn test
```

22 tests sur les couches pures : forme de l'attestation, alignement sur un `CFItem`, identité
par empreinte et normalisation de l'adresse, horodatage en temps universel, et surtout la chaîne
de vérification complète — signer ici, reconstruire la clé depuis le seul JWKS publié, et
vérifier. Un test tire vingt clés de suite pour que l'octet nul de tête d'un module RSA, qui ne
survient qu'une fois sur deux, ne puisse pas passer inaperçu.
