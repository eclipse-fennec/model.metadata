# model.metadata – Extraktions-Requirements

> [!CAUTION]
> **Historisches Dokument – dieses Projekt ist geschlossen.** Es beschreibt die ursprüngliche
> Extraktion aus `emf.codec` in dieses Repository. Inzwischen ist der Metadata-Service nach
> [`eclipse-fennec/emf.osgi`](https://github.com/eclipse-fennec/emf.osgi) migriert; dieses Repo wird
> nicht weiterentwickelt. Siehe [Projektstatus](project-status.md) und die
> [Portierungsanleitung](https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md).

| Feld        | Wert                                                                                  |
|-------------|---------------------------------------------------------------------------------------|
| Status      | Draft v0.2 – Kern-Extraktion durchgeführt (Build + Tests grün), s. §0                 |
| Ziel-Repo   | `eclipse-fennec/model.metadata` (dieses Workspace, Branch `main`)                     |
| Quelle      | Modul `org.eclipse.fennec.model.metadata` im Repo `eclipse-fennec/emf.codec`          |
| Zweck       | Den Model-Metadata-Service (Erstimplementierung) aus `emf.codec` in dieses eigene Repo herauslösen, ohne Funktionsverlust und ohne Bruch der Downstream-Konsumenten |
| Architektur | siehe `model-metadata-architecture.md` (mit dem Quellcode mit-extrahieren)            |

---

## 0. Stand der Umsetzung (2026-06-21)

**Durchgeführt — `./gradlew build` grün, alle Tests grün:**
- ✅ Bundle `org.eclipse.fennec.model.metadata` angelegt (`src/`, `src-gen/`, `src-gen-api/`, `test/`, `model/`),
  `bnd.bnd` übernommen. nsURIs/Package-Namen unverändert (FR-1..FR-4).
- ✅ `src-gen`/`src-gen-api` werden via `-generate` aus den genmodels reproduziert. (Hinweis: die
  `MalformedURLException` des EMF-Generators ist erwartetes, **nicht-fatales** Rauschen.)
- ✅ JUnit-5-Tests grün: `MetadataServiceImplTest` (44), `MapBasedMetadataIndexTest` (geschachtelt), `DiagnosticContainerTest` — 0 failures/errors.
- ✅ Architektur-Doku nach `docs/model-metadata-architecture.md` verschoben (FR-5).
- ✅ Repo-Scaffolding aus m2x (CI-Workflows, `LICENSE`, `.licenserc.yaml`, `.gitignore`) übernommen (s. §4.1).
- ✅ Workspace-Library **`fennecEMFMetadata`** angelegt (`…library.workspace`), `required.bndrun` aufgelöst;
  generierte `fennecEMFMetadata.maven` enthält `org.eclipse.fennec.metadata:org.eclipse.fennec.model.metadata:1.0.0-SNAPSHOT` + Laufzeit (§6.4, L-1..L-3).
- ✅ Workspace-Anpassungen: `-library` getrimmt (§4), `.classpath` → JavaSE-21, `bnd_exclude=build,docs`.
- ✅ Java durchgängig **21** (wie `emf.osgi`): `javac.source/target=21`, alle `.classpath` JavaSE-21, `-runee` JavaSE-21,
  genmodel `complianceLevel="21.0"` (beide), `src-gen`/`src-gen-api` im 21er-Stil regeneriert — 88 Tests grün.

**Offen (nächste Schritte):**
- ✅ Lizenz-Header: alle handgeschriebenen `src/`-/`test/`-Dateien tragen bereits den vollständigen EPL-2.0-Header (aus codec übernommen) — entspricht `.licenserc.yaml`.
- `emf.codec` auf das extrahierte Artefakt umstellen + internes Modul entfernen (I-1).
- Release-/Snapshot-Setup (Secrets) und offene Fragen Q1–Q6.

---

## 1. Kontext und Motivation

Der **Model Metadata Service** ist das generische Aspect-/Profile-Framework des Fennec-Stacks
(`MetadataRegistry → PackageMetadata → ClassMetadata/FeatureMetadata` mit pluggable `Aspect`s und
vorberechneten `Profile`s; `AspectProvider`/`MetadataWhiteboard`/`MetadataHandler`-SPIs). Er ist als
**architektonisches Fundament** für mehrere Concerns gedacht (Codec, OCL, ORM, OData, History, Units).

Heute liegt der Code als **Erstimplementierung im Repo `emf.codec`** (Modul
`org.eclipse.fennec.model.metadata`). Dadurch:

- ist `model.metadata` **nicht** in `emf.m2x` (OCL) und `emf.persistence-jpa` (JPA) verdrahtet;
- existieren die geplanten `OclAspectProvider` (Vorarbeit **VA1** für das OData-Vorhaben) und ein etwaiger
  `OrmAspectProvider` noch nicht – beide setzen ein eigenständig konsumierbares `model.metadata`-Artefakt voraus;
- ist `emf.codec` als alleiniger „Wirt" eine Schichtverletzung (Codec ist *Konsument*, nicht *Heimat* des Frameworks).

**Ziel dieser Extraktion:** `model.metadata` als eigenständiges, binär konsumierbares Bundle in diesem Repo
bereitstellen. Danach konsumieren `emf.codec`, `emf.m2x`, `emf.persistence-jpa` und das künftige
`emf.odata` es als Abhängigkeit.

---

## 2. Quelle (Source of Truth)

Zum Studium read-only verfügbar unter:
`/opt/git/fennec-odata/reference/fennec/emf.codec/org.eclipse.fennec.model.metadata/`

> Diese Referenz ist ein `--depth 1`-Klon (Branch `snapshot`). Für die tatsächliche Extraktion mit Historie
> ggf. einen vollen Klon von `emf.codec` heranziehen, falls Commit-Historie übernommen werden soll (s. Q3).

### 2.1 Bestand des Quell-Moduls

| Ordner        | Inhalt                                                              | Herkunft                         |
|---------------|--------------------------------------------------------------------|----------------------------------|
| `src/`        | 4 handgeschriebene Klassen (Package `…model.metadata.service`)     | hand-written                     |
| `src-gen/`    | 57 generierte Klassen aus `metadata.genmodel`                      | generiert                        |
| `src-gen-api/`| 18 generierte Klassen aus `metadata-api.genmodel`                  | generiert                        |
| `test/`       | 3 JUnit-5-Tests (Package `…model.metadata.service`)                | hand-written                     |
| `model/`      | `metadata.ecore` + `.genmodel`, `metadata-api.ecore` + `.genmodel` | Quelle der Generierung           |
| –             | `model-metadata-architecture.md`                                   | Architektur-Doku (mit-extrahieren) |

**Handgeschriebener Kern (`src/…service/`):**
- `MetadataServiceImpl` – Default-Implementierung von `MetadataWhiteboard` (Lifecycle für Packages/Provider)
- `MapBasedMetadataIndex` – thread-safer, Map-basierter `MetadataIndex`
- `MetadataServiceComponent` – OSGi-DS-Komponente, die `MetadataServiceImpl` als Whiteboard-Service exponiert
- `package-info.java` (`@Export`, Version 1.0)

### 2.2 EMF-Modelle

| Modell              | nsURI                                          | nsPrefix       | Top-Level (Auszug)                                                                                 |
|---------------------|------------------------------------------------|----------------|----------------------------------------------------------------------------------------------------|
| `metadata.ecore`    | `https://eclipse.org/fennec/metadata/1.0.0`    | `metadata`     | `MetadataRegistry`, `PackageMetadata`, `ClassMetadata`, `Attribute-/ReferenceMetadata`, `Aspect`-Hierarchie, `Package-/ClassProfile`, `Base*Config`, 7 Enums |
| `metadata-api.ecore`| `https://eclipse.org/fennec/metadata/api/1.0.0`| `metadata.api` | `MetadataService`, `MetadataWhiteboard`, `MetadataIndex(+Reader/Writer)`, `MetadataHandler`, `AspectProvider` |

Base-Package beider: `org.eclipse.fennec.model` bzw. `org.eclipse.fennec.model.metadata`. Beide Modelle
**zusammen** extrahieren – sie hängen voneinander ab.

### 2.3 Packages (API vs. intern)

| Package                                                | Rolle                          | Export |
|--------------------------------------------------------|--------------------------------|--------|
| `org.eclipse.fennec.model.metadata`                    | Public API (Modell)            | ja (`@Export`) |
| `…model.metadata.api`                                  | Public API (Service-Kontrakte) | ja (`@Export`) |
| `…model.metadata.service`                              | Public API (Service-Impl)      | ja (`@Export`) |
| `…model.metadata.impl` / `.api.impl`                   | generierte EMF-Impl            | ja (`@Export`) |
| `…model.metadata.util` / `.api.util`                   | EMF-Utilities                  | ja (`@Export`) |
| `…model.metadata.configuration` / `.api.configuration` | OSGi-DS + `EPackageConfigurator`| kein/implizit |

> Aktuell exportiert das Modul faktisch **alle** Packages (für service-orientierte Nutzung). Bei der
> Extraktion bewusst entscheiden, ob `impl`/`configuration` weiterhin exportiert bleiben (s. Q4).

---

## 3. Abhängigkeitsanalyse (kritisch)

**Befund: keine Zyklen. Der Graph ist einseitig.**

### 3.1 Externe Abhängigkeiten des Hauptcodes
Der gesamte Hauptcode (`src` + `src-gen` + `src-gen-api`) ist **bis auf eine** Abhängigkeit self-contained.
Einzige externe Fennec-Abhängigkeit:

| Importiertes Package                                  | genutzt von (4 Dateien)                                      |
|-------------------------------------------------------|--------------------------------------------------------------|
| `org.eclipse.fennec.emf.osgi.annotation.provide.EPackage` | `…configuration.MetadataConfigurationComponent`          |
| `org.eclipse.fennec.emf.osgi.configurator.EPackageConfigurator` | `…configuration.MetadataEPackageConfigurator`       |
| `org.eclipse.fennec.emf.osgi.constants.EMFNamespaces` | `…api.configuration.ApiConfigurationComponent`               |
|                                                       | `…api.configuration.ApiEPackageConfigurator`                 |

Das ist reine **EMF-OSGi-Infrastruktur**, kein Codec-Code. → Im Ziel-Workspace über die `fennecEMF`-Library
verfügbar (`cnf/ext/central.mvn` → `org.eclipse.fennec.emf.osgi.bnd.library.workspace`). **Keine Codec-Abhängigkeit.**

### 3.2 Test-Abhängigkeiten
Die Tests importieren **ausschließlich** modul-interne Klassen. Keine externen Fennec-Imports. → trivial mit-extrahierbar.

### 3.3 Reverse-Abhängigkeit (was bricht?)
`org.eclipse.fennec.codec.metadata` ist ein **starker Konsument** von `model.metadata` (eigener `-buildpath`-Eintrag
`org.eclipse.fennec.model.metadata;version=snapshot`, importiert nahezu alle Modell-/API-Klassen). Diese Richtung
(`codec.metadata → model.metadata`) ist genau die gewünschte. **Folge:** Nach der Extraktion muss `emf.codec` das
neue Artefakt aus diesem Repo beziehen statt aus dem eigenen Modul (s. §6).

```
model.metadata  ── hängt nur an ──▶  org.eclipse.fennec.emf.osgi.* (Infrastruktur)
       ▲
       └── konsumiert von ── emf.codec (codec.metadata), künftig: emf.m2x, emf.persistence-jpa, emf.odata
```

---

## 4. Ziel-Workspace (Ist-Zustand)

bndtools-Gradle-Workspace, identische Vorlage wie `fennec-odata`:

- **Koordinaten** (`gradle.properties`): `github_org=eclipse-fennec`, `github_repository=emf.model.metadata`,
  `maven_group_id=org.eclipse.fennec.metadata`.
  > ⚠️ Repo heißt real `model.metadata` (ohne `emf.`-Präfix), `github_repository` steht auf `emf.model.metadata`. Vor Release angleichen (Q1).
- **Libraries** (`cnf/ext/fennec.bnd`): auf `fennec, fennecTest, fennecJacoco, fennecEMF` **getrimmt** (war:
  zusätzlich `fennecCodec, fennecM2X, fennecJPA, fennecEMFModels`). Grund: `fennecCodec` hat **kein** Artefakt in
  `cnf/ext/central.mvn` (Build-Fehler `No -library for fennecCodec`); `fennecM2X`/`fennecJPA` wären
  **Abhängigkeitszyklen** (diese Repos hängen an `model.metadata`); `fennecEMFModels` wird nicht gebraucht.
  `fennecEMF` liefert die einzige externe Abhängigkeit `org.eclipse.fennec.emf.osgi.*`.
- **Java**: `javac.source/target = 21` (Workspace). Quell-`.classpath` von JavaSE-17 auf **21** angehoben (Q2 → erledigt).
- `bnd_exclude=build` ist gesetzt (Gradle-Output-Dir nicht als Projekt erfassen).
- Noch **keine** Bundle-Projekte vorhanden.
- **cnf-Shape:** Ziel nutzt das neuere „fennec-gradle"-Template (`cnf/build.bnd` + `cnf/ext/{central.mvn,fennec.bnd}`
  auf Basis `workspace-minimal`). m2x nutzt die ältere Variante (`cnf/central.mvn`, `cnf/ext/libraries.{bnd,maven}`,
  `cnf/local/`, `cnf/release/`, `cnf/templates/`). Die Differenz ist eine Template-Generation; das neuere Ziel-Layout
  wird beibehalten (kein Rückbau auf den m2x-cnf-Shape).

### 4.1 Repo-Scaffolding (Parität mit m2x) — ergänzt
Das Ziel-Repo war „nackt"; folgende, **projekt-agnostische** Dateien wurden aus m2x übernommen, damit es ein
vollwertiges Fennec-Repo ist:

| Datei                              | Zweck                                                              | Status |
|------------------------------------|--------------------------------------------------------------------|--------|
| `.github/workflows/build.yml`      | CI-Build (JDK 21 temurin, `./gradlew build`)                       | ✅ kopiert |
| `.github/workflows/license.yml`    | Lizenz-Header-Check via `apache/skywalking-eyes` gegen `.licenserc.yaml` | ✅ kopiert |
| `.github/workflows/release.yml`    | Release nach Maven Central (`./gradlew release`, Sonatype/GPG-Secrets) | ✅ kopiert |
| `.github/workflows/snapshot.yml`   | Snapshot-Deploy                                                    | ✅ kopiert |
| `.github/FUNDING.yml`              | Funding-Link (datainmotion)                                       | ✅ kopiert |
| `LICENSE`                          | EPL-2.0 Volltext                                                   | ✅ kopiert |
| `.licenserc.yaml`                  | Header-Konfig (EPL-2.0). **Jahr auf 2026**; zusätzlich `**/src-gen-api/**` ignoriert (Modul hat zwei Gen-Ordner) | ✅ angepasst |
| `.gitignore`                       | `.gradle/`, `build/`, `bin*/`, `generated/`, `cnf/cache`, `.metadata/` | ✅ kopiert |

**Offen / Folge-Setup:**
- Die Workflows sind generisch (kein m2x-Bezug); `release`/`snapshot` setzen org-/repo-weite Secrets voraus
  (`CENTRAL_SONATYPE_TOKEN_*`, `GPG_*`) — auf GitHub-Ebene zu hinterlegen, nicht im Repo.
- m2x besitzt zusätzlich `…bom`, `…library.project`, `…library.workspace` (eigene bnd-Library-/BOM-Publishing-Artefakte).
  **Entscheidung:** `model.metadata` veröffentlicht eine eigene Workspace-Library **`fennecEMFMetadata`** (s. §6.4).

---

## 5. Funktionale Anforderungen (Extraktion)

- **FR-1** Das Bundle-Projekt `org.eclipse.fennec.model.metadata` wird 1:1 in diesem Workspace neu angelegt
  (Ordner `src/`, `src-gen/`, `src-gen-api/`, `test/`, `model/`).
- **FR-2** Die beiden EMF-Modelle (`metadata.ecore`/`.genmodel`, `metadata-api.ecore`/`.genmodel`) werden mit-übernommen;
  `src-gen` und `src-gen-api` werden **aus den genmodels neu generiert** (nicht von Hand kopiert), Ergebnis muss
  byte-nah dem Original entsprechen.
- **FR-3** nsURIs und Package-Namen bleiben **unverändert**
  (`https://eclipse.org/fennec/metadata/1.0.0`, `…/api/1.0.0`, `org.eclipse.fennec.model.metadata*`) – sonst brechen
  alle Konsumenten und persistierte XMI-Profiles.
- **FR-4** Der `bnd.bnd` wird übernommen und an den Workspace angepasst (s. §6.1). `-generate`, `-includeresource.model`
  und `-library: enableEMF` bleiben erhalten.
- **FR-5** Die Architektur-Doku `model-metadata-architecture.md` zieht nach `docs/` dieses Repos um.
- **FR-6** Die handgeschriebenen Tests werden übernommen und müssen grün sein (s. §7).

## 6. Build- und Tooling-Anforderungen

### 6.1 bnd / OSGi
Aus dem Quell-`bnd.bnd` übernehmen, mit folgendem Soll-Stand:
```
Bundle-Name: Fennec Model Metadata
Bundle-Description: Services to hold model metadata

src=${^src},src-gen,src-gen-api

-library: enableEMF

-generate: \
	model/metadata.genmodel;     generate=fennecEMF; genmodel=model/metadata.genmodel;     output=src-gen,\
	model/metadata-api.genmodel; generate=fennecEMF; genmodel=model/metadata-api.genmodel; output=src-gen-api

-includeresource.model: model=model

-buildpath: \
	org.osgi.service.condition;version=latest
```
- **B-1** Die `emf.osgi.*`-Importe (§3.1) müssen über die Workspace-Libraries (`fennecEMF`) auf dem Buildpath landen.
  Falls nicht automatisch aufgelöst: gezielt `org.eclipse.fennec.emf.osgi…;version=latest` ergänzen.
- **B-2** Export-Strategie via `@Export` an den `package-info` beibehalten – kein explizites `Export-Package` im `bnd.bnd`
  (Konvention aus der Quelle), Entscheidung zu `impl`/`configuration`-Exports siehe Q4.
- **B-3** `./gradlew build` im Workspace baut das Bundle fehlerfrei.

### 6.2 Eclipse-IDE-Konventionen (verbindlich)
- **E-1** Projekt erhält `.project` (Natures `org.eclipse.jdt.core.javanature` + `bndtools.core.bndnature`;
  Builder `javabuilder` + `bndtools.core.bndbuilder`) und `.classpath`.
- **E-2** `.classpath`-Layout: Source-Folder `src` (→`bin`), `src-gen` (→`bin`), `src-gen-api` (→`bin`),
  `test` (→`bin_test`, `test=true`); Container `aQute.bnd.classpath.container`; **JRE-Container `JavaSE-21`** (nicht 17).
- **E-3** Tests laufen über die **eingebaute Eclipse-JUnit-Library** (`org.eclipse.jdt.junit.JUNIT_CONTAINER/5`),
  **nicht** über den bnd-Testpath.

### 6.3 Test
- **T-1** **Normale JUnit-5-Tests bevorzugt** (kein OSGi/`testOSGi`-Lauf für dieses Bundle).
  JUnit bleibt auf **Version 5** (6 noch nicht getestet).
- **T-2** `./gradlew :org.eclipse.fennec.model.metadata:test` ist grün (3 vorhandene Tests + ggf. ergänzte).

### 6.4 Workspace-Library `fennecEMFMetadata` (verbindlich)
`model.metadata` stellt eine **bnd-Workspace-Library** bereit, die Downstream-Workspaces
(`emf.codec`, `emf.m2x`, `emf.persistence-jpa`, `emf.odata`) per `-library: fennecEMFMetadata`
in ihrer `cnf/ext/fennec.bnd` aktivieren – exakt analog zu `fennecM2X`/`fennecEMF`/`fennecJPA`.

- **L-1** **Library-Name: `fennecEMFMetadata`** (fix, von außen referenziert).
- **L-2** Realisiert über ein zusätzliches Projekt `org.eclipse.fennec.model.metadata.library.workspace`,
  Vorbild: `org.eclipse.fennec.m2x.library.workspace`. Aufbau:
  - `bnd.bnd`: `-resourceonly: true`, `-include: ${.}/buildpath.bnd`, `-includeresource: {resources}` und
    ```
    Provide-Capability: ${fennec-base}
    fennec-base: bnd.library; bnd.library = fennecEMFMetadata; path = template
    ```
  - `required.bndrun`: `-runfw` Felix, `-runee JavaSE-21`, `-runrequires` auf das Bundle
    `org.eclipse.fennec.model.metadata`; das aufgelöste `-runbundles` liefert die transitive Laufzeit
    (EMF common/ecore/xmi, felix.scr, osgi.service.*, `org.eclipse.fennec.emf.osgi.component.minimal`).
  - `buildpath.bnd`: `-buildpath: ${-runbundles}` plus die OSGi-`-buildpath.extra` (condition, component.annotations,
    framework, annotation.versioning/bundle).
  - `resources/template/workspace.bnd`: `-plugin.fennecEMFMetadata: …MavenBndRepository… index="${.}/fennecEMFMetadata.maven"; name="Eclipse Fennec EMF Metadata Dependencies - ${Bundle-Version}"` + `-require-bnd: "(version>=7.2.0)"`.
  - `resources/template/fennecEMFMetadata.maven`: Inhalt `${mavendeps}` (aus dem `template`-Macro generiert).
- **L-3** Akzeptanz: Ein fremder Workspace, der `-library: fennecEMFMetadata` setzt, bekommt
  `org.eclipse.fennec.model.metadata` (+ EMF-Laufzeit) ohne weitere Repository-Konfiguration auf den Buildpath.

> Optional analog m2x: ein `…library.project` mit per-Projekt-Enable-Libraries (z. B. `enableMetadata`) und ein
> `…bom`. Nicht zwingend für L-1..L-3; bei Bedarf nachziehen.

---

## 7. Nach der Extraktion (Integration / Downstream)

- **I-1** `emf.codec` (Modul `codec.metadata`) bezieht `model.metadata` künftig als **veröffentlichtes Artefakt**
  dieses Repos (Maven-Group `org.eclipse.fennec.metadata`), nicht mehr aus dem internen Modul. Der eigene
  `org.eclipse.fennec.model.metadata`-Ordner in `emf.codec` wird anschließend entfernt.
- **I-2** Damit wird der Weg frei für **VA1** (`OclAspectProvider` in `emf.m2x`) und einen etwaigen
  `OrmAspectProvider` in `emf.persistence-jpa` – beide hängen an einem eigenständig konsumierbaren `model.metadata`.
- **I-3** Versionierung/Release: Über GitHub Actions auf Maven Central (gemäß `cnf/ext/fennec.bnd`-Kommentar),
  `-groupid: org.eclipse.fennec.metadata`.

---

## 8. Akzeptanzkriterien

1. `./gradlew build` im Workspace erfolgreich; Bundle `org.eclipse.fennec.model.metadata` wird erzeugt.
2. `src-gen`/`src-gen-api` aus den genmodels reproduzierbar generierbar; nsURIs und Package-Namen unverändert.
3. Alle übernommenen JUnit-5-Tests grün (`:org.eclipse.fennec.model.metadata:test`).
4. Projekt in Eclipse importierbar (`.project`/`.classpath`, JavaSE-21, JUnit-5-Container, bnd-Container).
5. Gegenprobe: `emf.codec` baut weiterhin, wenn es das extrahierte Artefakt statt des internen Moduls konsumiert.

---

## 9. Offene Fragen

| #  | Frage                                                                                                  |
|----|--------------------------------------------------------------------------------------------------------|
| Q1 | `github_repository` in `gradle.properties` steht auf `emf.model.metadata`, Repo heißt `model.metadata`. Angleichen – und wenn ja, auf welchen Namen? |
| Q2 | Java-Level: Quelle 17, Workspace 21. Auf 21 anheben (empfohlen) – bestätigt?                            |
| Q3 | Commit-Historie aus `emf.codec` übernehmen (z. B. `git filter-repo` auf das Modul) oder sauberer Initial-Commit? |
| Q4 | Export-Sichtbarkeit: bleiben `…impl` und `…configuration` exportiert, oder werden sie zu Private-Package? |
| Q5 | Wird der `model.metadata`-Ordner in `emf.codec` im selben Schritt entfernt (PR dort) oder erst nach Release dieses Repos? |
| Q6 | Branch-Strategie hier: bei `main` bleiben oder Fennec-Konvention `snapshot`/`develop` einführen?         |
| ~~Q7~~ | ~~Eigene bnd-Library veröffentlichen?~~ → **entschieden:** ja, Workspace-Library **`fennecEMFMetadata`** (s. §6.4). Optional `…library.project`/`…bom` später. |

---

## Quellen
- Architektur: `docs/model-metadata-architecture.md` (aus `emf.codec` mit-extrahiert)
- Inventur-Basis: Modul `org.eclipse.fennec.model.metadata` in `reference/fennec/emf.codec/` (Stand snapshot, 2026-06-21)
- Vorbild für Aspect-Erweiterung: `org.eclipse.fennec.codec.metadata` (Konsument)
