#!/usr/bin/env python3
"""Catalogue de badges des programmes de l'école primaire, tiré du référentiel CASE du module case.

Un badge par sous-domaine et par cycle (« Cycle 2 · Mathématiques · Les quatre opérations ») ; un
enseignement sans sous-domaine (arts plastiques, éducation musicale…) en reçoit un par domaine.
Les nouveaux programmes n'ont plus d'attendus de fin de cycle : le sous-domaine est leur unité.

Chaque badge porte la liste des énoncés qu'il couvre (lui-même et tous ses descendants) : un
exercice aligné sur l'un d'eux en est une preuve possible. Les rubriques d'un programme abrogé ne
sont retenues que si l'enseignement n'en a pas d'autre.

Identifiants stables (UUID v5 de l'identifiant du CFItem) : régénérer le catalogue met les badges
à jour sans toucher aux attestations déjà délivrées.

    python3 deployment/catalogue/build.py ../case/src/main/resources/referentiels/programmes-1d.json
"""
import json
import re
import sys
import uuid
from pathlib import Path

NAMESPACE = uuid.UUID('9d3c2f1e-7b4a-5c6d-8e9f-0a1b2c3d4e5f')
OUT = Path(__file__).resolve().parents[2] / 'src/main/resources/catalogue/programmes-1d.json'

pkg = json.loads(Path(sys.argv[1]).read_text())
document = pkg['CFDocument']
items = {i['identifier']: i for i in pkg['CFItems']}
children, parent = {}, {}
for a in pkg['CFAssociations']:
    if a['associationType'] == 'isChildOf':
        child, par = a['originNodeURI']['identifier'], a['destinationNodeURI']['identifier']
        children.setdefault(par, []).append(child)
        parent[child] = par


def order(identifier):
    return [int(n) if n.isdigit() else n for n in re.split(r'(\d+)', items[identifier].get('humanCodingScheme', ''))]


def under(identifier, kind):
    return [k for k in sorted(children.get(identifier, []), key=order) if items[k].get('CFItemType') == kind]


def descendants(identifier):
    found = [identifier]
    for child in children.get(identifier, []):
        found += descendants(child)
    return found


def clean(statement):
    return re.sub(r'^Thème \d+\s*:\s*', '', statement).strip().rstrip('.')


badges = []
for cycle in sorted((i for i in items.values() if i.get('CFItemType') == 'Cycle'), key=lambda i: i['humanCodingScheme']):
    cycle_label = cycle['fullStatement'].split(' — ')[0]
    for subject in under(cycle['identifier'], 'Enseignement'):
        subject_name = items[subject]['fullStatement']
        domains = under(subject, 'Domaine')
        domains = [d for d in domains if not items[d].get('statusEndDate')] or domains
        for domain in domains:
            for unit in under(domain, 'Sous-domaine') or [domain]:
                item = items[unit]
                covered = descendants(unit)
                objectives = [items[c]['fullStatement'] for c in covered
                              if c != unit and items[c].get('CFItemType') not in ('Sous-domaine', 'Domaine')]
                levels = [l for l in item.get('educationLevel', []) if l]
                badges.append({
                    'id': str(uuid.uuid5(NAMESPACE, item['identifier'])),
                    'caseItem': item['identifier'],
                    'code': item.get('humanCodingScheme'),
                    'cycle': cycle['humanCodingScheme'],
                    'subject': subject_name,
                    'levels': levels,
                    'name': f"{cycle_label} · {subject_name} · {clean(item['fullStatement'])}",
                    'description': (f"Atteste que l'élève a atteint les objectifs du programme de {subject_name.lower()} "
                                    f"au {cycle_label.lower()} pour « {clean(item['fullStatement'])} »."),
                    'criteria': ("Un enseignant de l'établissement a constaté que l'élève atteint les objectifs "
                                 "d'apprentissage suivants du programme officiel :\n"
                                 + '\n'.join(f'- {o}' for o in objectives)) if objectives else
                                ("Un enseignant de l'établissement a constaté que l'élève atteint les objectifs "
                                 f"du programme officiel pour « {clean(item['fullStatement'])} »."),
                    'targetName': clean(item['fullStatement']),
                    'objectives': covered,
                })

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({
    'framework': document['identifier'],
    'frameworkTitle': document['title'],
    'badges': badges,
}, ensure_ascii=False, indent=1) + '\n')
print(f'{OUT.name} : {len(badges)} badges, '
      + ', '.join(f"{c} {sum(1 for b in badges if b['cycle'] == c)}" for c in ('C1', 'C2', 'C3')))
