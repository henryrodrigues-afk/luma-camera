// Read build metadata without running Gradle. All destinations stay inside dist/.
const fs = require('node:fs');
const path = require('node:path');

function releaseVersionFromGradle(source) {
  const matches = [...source.matchAll(/^\s*versionName\s*=\s*"([^"]+)"/gm)];
  if (matches.length !== 1) throw Error('Expected one literal versionName in app/build.gradle.kts.');
  const versionName = matches[0][1];
  const version = /^(\d+)\.(\d+)(?:\.\d+)?(?:[-+][A-Za-z0-9.-]+)?$/.exec(versionName);
  if (!version) throw Error('versionName must start with a numeric major.minor version.');
  return { versionName, releaseVersion: `${version[1]}.${version[2]}` };
}

const projectRoot = path.resolve(__dirname, '..', '..');
const metadata = releaseVersionFromGradle(fs.readFileSync(path.join(projectRoot, 'app', 'build.gradle.kts'), 'utf8'));
const releaseDirectory = path.join(projectRoot, 'dist', 'releases', metadata.releaseVersion);
const reportDirectory = path.join(projectRoot, 'dist', 'reports', metadata.releaseVersion);
const projectPaths = Object.freeze({
  projectRoot,
  ...metadata,
  releaseDirectory,
  apkDirectory: path.join(releaseDirectory, 'apk'),
  sourceDirectory: path.join(releaseDirectory, 'source'),
  reportDirectory,
  testDirectory: path.join(reportDirectory, 'tests'),
  rawReportDirectory: path.join(reportDirectory, 'raw'),
  uiDirectory: path.join(reportDirectory, 'ui'),
  lutDirectory: path.join(projectRoot, 'dist', 'resources', 'luts'),
  previewDirectory: path.join(projectRoot, 'dist', 'resources', 'previews'),
  fixtureDirectory: path.join(projectRoot, 'dist', 'resources', 'fixtures'),
});

module.exports = { projectPaths, releaseVersionFromGradle };
