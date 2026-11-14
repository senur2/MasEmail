#!/usr/bin/env python3
"""Verify local JSON Schema and examples. Uses jsonschema (development only)."""
from pathlib import Path
import json
from jsonschema import Draft202012Validator, FormatChecker

root=Path(__file__).parent
schemas=root/'schemas'
examples=root/'examples'
for path in schemas.rglob('*.json'):
    Draft202012Validator.check_schema(json.loads(path.read_text(encoding='utf-8')))

valids=0
for path in (examples/'valid').glob('*.json'):
    stem=path.stem
    kind='payload' if stem in {'user-discussion-answer','discussion-resume'} else 'message'
    if kind=='message':stem=stem.rsplit('.',1)[0]
    schema=json.loads((schemas/kind/(stem+'.schema.json')).read_text(encoding='utf-8'))
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(json.loads(path.read_text(encoding='utf-8')))
    valids+=1
invalids=0
for path in (examples/'invalid').glob('*.json'):
    stem=path.stem.rsplit('.',1)[0]
    schema=json.loads((schemas/'message'/(stem+'.schema.json')).read_text(encoding='utf-8'))
    if Draft202012Validator(schema,format_checker=FormatChecker()).is_valid(json.loads(path.read_text(encoding='utf-8'))):
        raise AssertionError(f'Invalid example erroneously accepted: {path.name}')
    invalids+=1

# Check that LLM output schemas accept only the cognitive fields, without Java-owned IDs.
for name,full in {
    'email-analysis': 'email-analysis.success',
    'route-decision': 'route-decision.selected',
    'response-plan': 'response-plan.ready',
    'correction-plan': 'correction-plan.ready',
    'draft-content': 'draft-response.draft',
}.items():
    full=json.loads((examples/'valid'/(full+'.json')).read_text(encoding='utf-8'))['payload']
    content_schema=json.loads((schemas/'llm'/(name+'.schema.json')).read_text(encoding='utf-8'))
    cognitive={key:full[key] for key in content_schema['properties']}
    Draft202012Validator(content_schema).validate(cognitive)
    if name!='correction-plan' and Draft202012Validator(content_schema).is_valid(full):
        raise AssertionError(f'LLM schema accepts Java-owned fields for {name}')

print(f'OK: {len(list(schemas.rglob("*.json")))} schemas, {valids} valid examples, {invalids} rejected invalid examples and 5 LLM outputs')
