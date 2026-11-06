-- Four more supporting-document types, from the business's mapping of the Power App's types onto this
-- service's (Steve, 2026-11-06). The Power App files documents under these four and V39 has nothing they could
-- go under, so without them the migrated documents of these types could not be filed.
--
-- Same shape as V39's insert: each type is added only if no type of that name exists, keyed on the name rather
-- than the id, and no existing row is changed. Every other column takes V39's values for a non-deed type: empty
-- applicability, meaning any case type and any marital status.
--
-- The ids are fixed so every environment gives a type the same id. They are UUIDv5 in the URL namespace of
--   https://github.com/hmcts/deed-poll-api/supporting-document-type/<name>
-- for example in Python: uuid.uuid5(uuid.NAMESPACE_URL, "https://github.com/hmcts/deed-poll-api/supporting-document-type/Letter of consent").
-- (V39's ids are also UUIDv5 of the name, but it did not record its namespace, so these are not derived the
-- same way. Nothing depends on that: an id is only ever looked up, never recomputed.)
INSERT INTO "deedpoll_supportingdocumenttype" ("id", "name", "applicableMaritalStatuses", "applicableTypes")
SELECT v."id", v."name", ARRAY[]::integer[], ARRAY[]::integer[]
  FROM (VALUES
    ('a5ad0d5d-626e-5e3d-b511-3711588b4246', 'Letter of consent'),
    ('84a9c547-5ea7-5413-b032-fca38c2113ba', 'Minors Consent Letter (16-17 years)'),
    ('41de8383-4817-5aad-915d-2b03ad30d114', 'Notice for the Gazette (LOC025)'),
    ('0f025165-5de0-51e6-97ec-8aec08c53ca8', 'Notice for the Gazette for a minor (LOC026)')
  ) AS v("id", "name")
 WHERE NOT EXISTS (SELECT 1 FROM "deedpoll_supportingdocumenttype" t WHERE t."name" = v."name")
ON CONFLICT ("id") DO NOTHING;

-- Rolling back the application below this version needs nothing: the old code reads document types from the
-- table, and these four are ordinary rows to it.
