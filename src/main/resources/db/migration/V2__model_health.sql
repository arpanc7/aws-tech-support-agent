CREATE TABLE model_health (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  healthy boolean NOT NULL DEFAULT true,
  reason text NOT NULL DEFAULT ''
);
INSERT INTO model_health(singleton) VALUES(true);
