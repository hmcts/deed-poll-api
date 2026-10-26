-- The business's supporting-document types (Steve, 2026-10-26), so every environment offers the same list.
-- Until now nothing seeded this table: the api created its two deed types on first use, and nothing else
-- existed, so a caseworker could only file a document under a deed's type.
--
-- 1. Rename the api's two deed types to the business's names, in place. The id is what each document
--    references, so a document already filed under one (AAT and ITHC each hold an LOC022 row with documents)
--    keeps its type. DeedDocumentTypes still recognises the old names for one release.
UPDATE "deedpoll_supportingdocumenttype" SET "name" = 'Change of Name Deed (LOC020)'
 WHERE "name" = 'LOC020 Change of name deed';
UPDATE "deedpoll_supportingdocumenttype" SET "name" = 'Change of Name Deed for a minor (LOC022)'
 WHERE "name" = 'LOC022 Change of name deed for a child';

-- 2. Insert each type whose name is not already present. Keyed on the name, not the id, so a renamed deed row
--    above (or a type created some other way) is never duplicated. The ids are fixed (UUIDv5 of the name), so
--    every environment that inserts a type gives it the same id.
--    applicableTypes: the two deeds keep the case types the api gives them (LOC020: Adult 1, AdultTransgender 2;
--    LOC022: Minor 3, MinorTransgender 4). Everything else is empty, meaning any, as is every marital status.
INSERT INTO "deedpoll_supportingdocumenttype" ("id", "name", "applicableMaritalStatuses", "applicableTypes")
SELECT v."id", v."name", ARRAY[]::integer[], v."applicableTypes"
  FROM (VALUES
    ('86dd1554-50ae-5653-a950-a5204b2163da', 'Master''s Directions', '{}'::integer[]),
    ('cb4a7d0c-cc7c-5989-b382-585c7dcb204e', 'Sworn Affidavit', '{}'::integer[]),
    ('a370c09f-6d80-5bda-8a3f-0da5c0eeb322', 'Applicant response to Master''s Directions', '{}'::integer[]),
    ('e5486d06-3673-52fc-8d89-9b7fa7ce8684', 'British, British Territories or Commonwealth Citizen Evidence/Naturalisation Certificate', '{}'::integer[]),
    ('857b6280-f78b-5462-82d2-807f7d065d9d', 'Cancel Request', '{}'::integer[]),
    ('4b80ce0d-a273-58c5-8d23-6d87b5630c73', 'CHANGE OF ADDRESS', '{}'::integer[]),
    ('c07da2f7-065d-5233-8882-018f8379cbe4', 'Change of Name Deed (LOC020)', '{1,2}'::integer[]),
    ('0fecde95-b455-59c9-852c-18d50bf32518', 'Statutory Declaration (LOC021)', '{}'::integer[]),
    ('a08c059b-9450-5150-b169-8374fb5d3c72', 'Change of Name Deed for a minor (LOC022)', '{3,4}'::integer[]),
    ('37432d3b-6b29-5d7c-84d4-9013fa0bb7c8', 'Affidavit of best interest (LOC023)', '{}'::integer[]),
    ('dacbfd0c-4482-52b9-9c46-e9d0f28dda4d', 'Statutory Declaration (LOC024)', '{}'::integer[]),
    ('472410bf-eb85-5d41-b3aa-7e42edaf8c09', 'Consent of Ex-Partner', '{}'::integer[]),
    ('262d44a3-2a9e-56bd-a0ba-06854bd665a7', 'Consent of new partner/stepparent', '{}'::integer[]),
    ('36269769-1e77-58fd-a127-4137c615426a', 'Consent of Spouse', '{}'::integer[]),
    ('2ffed9b6-00b6-5fac-8aef-f5ecb6e9fcb8', 'Copy Adoption Certificate', '{}'::integer[]),
    ('0ccfdcec-d39e-5399-bb86-33a610c37bbc', 'Copy Birth Certificate', '{}'::integer[]),
    ('b02af74c-d2c2-5fc2-931f-7d1e114e91ac', 'Copy Civil Partnership Certificate', '{}'::integer[]),
    ('f48c5edd-1ee1-5074-82ae-301f9b3af703', 'Copy Marriage Certificate', '{}'::integer[]),
    ('a798967b-c400-5538-bbcd-18f921e15f73', 'Copy Naturalisation Certificate', '{}'::integer[]),
    ('6fb94b14-1f3d-57a7-bda5-514902051e84', 'Copy Passport', '{}'::integer[]),
    ('fc105581-9e60-57b8-b077-a4fa74f5e19b', 'Deed of Relinquishment', '{}'::integer[]),
    ('b5afa987-5e80-5494-bcd7-9e6f24cc661c', 'Enrolled Deed', '{}'::integer[]),
    ('c716123a-fbdf-565b-8435-ddd0b662ee38', 'Enrolled Deed of Relinquishment', '{}'::integer[]),
    ('1b09b52e-1173-5837-9e52-7b4be5032d53', 'Unenrolled Deed', '{}'::integer[]),
    ('b985cd0a-f660-57a9-96fc-09ff1d149797', 'Fee Payment', '{}'::integer[]),
    ('b55b1e78-5675-5f32-8c2d-5fac2e88de74', 'Court Order', '{}'::integer[]),
    ('f2d3454c-952a-5970-824f-fb9be270515b', 'Final order of divorce/dissolution', '{}'::integer[]),
    ('cafdb17e-6167-5b3e-bb5e-8d8821d14c2e', 'OTHER Documents', '{}'::integer[]),
    ('ef440e46-1d5f-5f3e-9c3e-604a2eeee6c8', 'Other Supporting Document', '{}'::integer[]),
    ('aca66af3-f5e5-5971-b711-b78bf3e38019', 'Partners Death Certificate', '{}'::integer[]),
    ('67f152ef-d6a0-5d8c-a08d-c86f56977c82', 'Referral Form', '{}'::integer[]),
    ('05dd0563-0c3a-59ab-aced-fb938d667dc2', 'Return Letter', '{}'::integer[])
  ) AS v("id", "name", "applicableTypes")
 WHERE NOT EXISTS (SELECT 1 FROM "deedpoll_supportingdocumenttype" t WHERE t."name" = v."name")
ON CONFLICT ("id") DO NOTHING;

-- Rolling back the application below this version: the old code looks the deed types up by their old names
-- only, so first rename the two rows back, or its next deed creates a second type and stops seeing the first:
--   UPDATE "deedpoll_supportingdocumenttype" SET "name" = 'LOC020 Change of name deed'
--    WHERE "name" = 'Change of Name Deed (LOC020)';
--   UPDATE "deedpoll_supportingdocumenttype" SET "name" = 'LOC022 Change of name deed for a child'
--    WHERE "name" = 'Change of Name Deed for a minor (LOC022)';
-- The seeded rows can stay: the old code ignores them.
