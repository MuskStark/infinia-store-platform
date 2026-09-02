-- APP releases can carry installed and portable distributions for the same
-- platform/architecture, plus parallel lite/JRE/UOS/JAR variants.
ALTER TABLE release_artifact ADD COLUMN variant VARCHAR(32) NOT NULL DEFAULT 'default';
ALTER TABLE release_artifact DROP CONSTRAINT pk_release_artifact;
ALTER TABLE release_artifact ADD CONSTRAINT pk_release_artifact
    PRIMARY KEY (release_id, platform, arch, kind, variant);

ALTER TABLE upload_session ADD COLUMN variant VARCHAR(32) NOT NULL DEFAULT 'default';
