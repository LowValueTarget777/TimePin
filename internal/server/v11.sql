CREATE TABLE IF NOT EXISTS android_releases (
  package TEXT NOT NULL, code INTEGER NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL,
  sha256 TEXT NOT NULL, bytes INTEGER NOT NULL, created INTEGER NOT NULL, published INTEGER NOT NULL,
  PRIMARY KEY(package,code)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS android_release_chunks (
  package TEXT NOT NULL, code INTEGER NOT NULL, part INTEGER NOT NULL, data BLOB NOT NULL,
  PRIMARY KEY(package,code,part),
  FOREIGN KEY(package,code) REFERENCES android_releases(package,code) ON DELETE CASCADE
) WITHOUT ROWID;
PRAGMA user_version=11;
