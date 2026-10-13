//! Finds a Java 25 runtime for dev builds (Gradle toolchain cache and similar JDK folders).

use std::path::{Path, PathBuf};

pub const REQUIRED_MAJOR: u32 = 25;

#[cfg(windows)]
pub const JAVA_EXE: &str = "java.exe";
#[cfg(not(windows))]
pub const JAVA_EXE: &str = "java";

/// Vendor prefixes that may be glued to the version, e.g. `jdk25` or `zulu25.28.85-ca-jdk25.0.0`.
const VERSION_PREFIXES: [&str; 5] = ["openjdk", "jdk", "jre", "zulu", "temurin"];

/// Major version encoded in a JDK directory name: the first numeric token, e.g.
/// `jdk-25.0.1+8`, `eclipse_adoptium-25-amd64-windows.2`, `temurin-25`.
pub fn major_from_dir_name(name: &str) -> Option<u32> {
    name.split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|token| !token.is_empty())
        .find_map(|token| {
            let lower = token.to_ascii_lowercase();
            let digits = VERSION_PREFIXES
                .iter()
                .find_map(|prefix| lower.strip_prefix(prefix))
                .unwrap_or(&lower);
            // Tokens like `amd64` or `x64` fail this check and are skipped.
            if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
                digits.parse().ok()
            } else {
                None
            }
        })
}

/// `JAVA_VERSION="25.0.1"` from a JDK `release` file.
pub fn major_from_release(contents: &str) -> Option<u32> {
    contents.lines().find_map(|line| {
        let value = line.trim().strip_prefix("JAVA_VERSION=")?.trim().trim_matches('"');
        value.split(['.', '+', '-']).next()?.parse().ok()
    })
}

fn file_name_is(path: &Path, expected: &str) -> bool {
    path.file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.eq_ignore_ascii_case(expected))
}

/// Major version of the JDK owning `java`, judged from `<home>` or, for nested layouts, its parent.
fn major_from_java_path(java: &Path) -> Option<u32> {
    if !file_name_is(java, JAVA_EXE) {
        return None;
    }
    let bin = java.parent().filter(|p| file_name_is(p, "bin"))?;
    let home = bin.parent()?;
    let dir_major = |dir: &Path| dir.file_name().and_then(|n| n.to_str()).and_then(major_from_dir_name);
    dir_major(home).or_else(|| home.parent().and_then(dir_major))
}

/// Keeps `<jdk home>/bin/java(.exe)` paths whose directory names say Java 25. Best match first.
pub fn filter_java25(candidates: &[PathBuf]) -> Vec<PathBuf> {
    let mut matches: Vec<PathBuf> = candidates
        .iter()
        .filter(|p| major_from_java_path(p) == Some(REQUIRED_MAJOR))
        .cloned()
        .collect();
    matches.sort_by(|a, b| b.cmp(a));
    matches.dedup();
    matches
}

/// `<root>/*/bin/java` and `<root>/*/*/bin/java` (newer Gradle versions nest the JDK one level).
pub fn list_candidates(roots: &[PathBuf]) -> Vec<PathBuf> {
    let subdirs = |dir: &Path| -> Vec<PathBuf> {
        std::fs::read_dir(dir)
            .map(|entries| {
                entries
                    .filter_map(Result::ok)
                    .map(|e| e.path())
                    .filter(|p| p.is_dir())
                    .collect()
            })
            .unwrap_or_default()
    };
    let mut found = Vec::new();
    for root in roots {
        for home in subdirs(root) {
            for dir in std::iter::once(home.clone()).chain(subdirs(&home)) {
                let java = dir.join("bin").join(JAVA_EXE);
                if java.is_file() {
                    found.push(java);
                }
            }
        }
    }
    found
}

/// Directories searched when `ARCTIC_JAVA` is not set.
pub fn default_roots(user_profile: Option<&Path>) -> Vec<PathBuf> {
    user_profile
        .map(|home| vec![home.join(".gradle").join("jdks"), home.join(".jv")])
        .unwrap_or_default()
}

/// First Java 25 under `roots`, skipping JDKs whose `release` file names another version.
pub fn discover_java25(roots: &[PathBuf]) -> Option<PathBuf> {
    filter_java25(&list_candidates(roots)).into_iter().find(|java| {
        let release = java.parent().and_then(Path::parent).map(|home| home.join("release"));
        match release.and_then(|r| std::fs::read_to_string(r).ok()) {
            Some(contents) => major_from_release(&contents) == Some(REQUIRED_MAJOR),
            None => true,
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn java(parts: &[&str]) -> PathBuf {
        let mut path = PathBuf::from("C:\\Users\\u\\.gradle\\jdks");
        for part in parts {
            path.push(part);
        }
        path.join("bin").join(JAVA_EXE)
    }

    #[test]
    fn parses_major_from_common_dir_names() {
        assert_eq!(major_from_dir_name("jdk-25"), Some(25));
        assert_eq!(major_from_dir_name("jdk-25.0.1+8"), Some(25));
        assert_eq!(major_from_dir_name("eclipse_adoptium-25-amd64-windows.2"), Some(25));
        assert_eq!(major_from_dir_name("amazon-corretto-21.0.4.7.1-windows-x64"), Some(21));
        assert_eq!(major_from_dir_name("jdk25"), Some(25));
        assert_eq!(major_from_dir_name("zulu25.28.85-ca-jdk25.0.0-win_x64"), Some(25));
        assert_eq!(major_from_dir_name("windows-amd64"), None);
        assert_eq!(major_from_dir_name("jdk"), None);
    }

    #[test]
    fn keeps_only_java25_executables() {
        let candidates = vec![
            java(&["eclipse_adoptium-21-amd64-windows.2"]),
            java(&["eclipse_adoptium-25-amd64-windows.2"]),
            java(&["jdk-250"]),
            java(&["jdk-19.0.2"]),
            PathBuf::from("C:\\jdk-25\\bin\\javaw.exe"),
            PathBuf::from("C:\\jdk-25\\lib\\").join(JAVA_EXE),
        ];
        assert_eq!(filter_java25(&candidates), vec![java(&["eclipse_adoptium-25-amd64-windows.2"])]);
    }

    #[test]
    fn uses_parent_dir_for_nested_gradle_layout() {
        let nested = java(&["eclipse_adoptium-25-amd64-windows", "jdk"]);
        let wrong = java(&["eclipse_adoptium-21-amd64-windows", "jdk"]);
        assert_eq!(filter_java25(&[wrong, nested.clone()]), vec![nested]);
    }

    #[test]
    fn inner_dir_version_wins_over_parent() {
        let inner21 = java(&["something-25", "jdk-21.0.1"]);
        assert!(filter_java25(&[inner21]).is_empty());
    }

    #[test]
    fn prefers_later_paths_and_dedups() {
        let a = java(&["jdk-25.0.1"]);
        let b = java(&["jdk-25.0.2"]);
        assert_eq!(filter_java25(&[a.clone(), b.clone(), a.clone()]), vec![b, a]);
    }

    #[test]
    fn executable_name_is_case_insensitive() {
        let upper = PathBuf::from("C:\\jdk-25\\BIN").join(JAVA_EXE.to_ascii_uppercase());
        assert_eq!(filter_java25(std::slice::from_ref(&upper)), vec![upper]);
    }

    #[test]
    fn parses_release_file() {
        let contents = "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.4.1\"\nOS_NAME=\"Windows\"\n";
        assert_eq!(major_from_release(contents), Some(25));
        assert_eq!(major_from_release("JAVA_VERSION=\"21\""), Some(21));
        assert_eq!(major_from_release("JAVA_VERSION=\"1.8.0_392\""), Some(1));
        assert_eq!(major_from_release("nothing"), None);
    }

    #[test]
    fn default_roots_need_a_profile() {
        assert!(default_roots(None).is_empty());
        let roots = default_roots(Some(Path::new("C:\\Users\\u")));
        assert_eq!(roots[0], Path::new("C:\\Users\\u\\.gradle\\jdks"));
    }
}
