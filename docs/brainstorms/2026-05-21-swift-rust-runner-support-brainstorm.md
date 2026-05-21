---
date: 2026-05-21
topic: swift-rust-runner-support
---

# Swift and Rust Runner Support

## What We're Building

LearnLoop will add Swift and Rust as fully supported practice languages. "Supported" means users can filter, open, edit, sync, submit, and run practice exercises in these languages, not only tag or display them.

Swift practice execution will use Swift Package Manager with XCTest. Rust practice execution will use Cargo with `cargo test`. This keeps the exercise experience aligned with what developers already expect from each ecosystem.

## Why This Approach

We considered a lighter language-tag-only approach, but that would make Swift/Rust look supported while the Run workflow still fails. That creates a poor first-use experience for a learning platform.

The chosen approach extends the existing LearnLoop pattern: each runnable language has a server-side contract, a runner registry entry, a Docker runner image, smoke tests, and frontend editor/filter support. Runner images are installed on demand so the default installable package stays small.

Runner image size is the main constraint behind selective installation. Rust adds roughly 260-290MB compressed, while Swift adds roughly 1.0GB compressed before runtime expansion, so bundling every runner by default would create too much installation burden.

## Key Decisions

- **Full runner support**: Swift/Rust support includes practice execution, not just metadata, filtering, or syntax highlighting.
- **Swift execution model**: Swift uses SwiftPM and XCTest.
- **Rust execution model**: Rust uses Cargo and `cargo test`.
- **Template generation trigger**: Swift/Rust-specific practice templates are generated only when the collected evidence has a clear Swift or Rust primary language. Otherwise, generation stays language-neutral.
- **Default Swift template**: `Package.swift`, `Sources/LearnLoopPractice/Solution.swift`, and `Tests/LearnLoopPracticeTests/SolutionTests.swift`.
- **Default Rust template**: `Cargo.toml`, `src/lib.rs`, and `tests/solution_test.rs`.
- **Runner installation model**: The default release bundle is online-first and does not need to include every runner image. During first setup, the local user selects languages, and LearnLoop downloads only the selected runner images.
- **First setup defaults**: TypeScript, Kotlin, and Java are selected by default. Swift and Rust are available but require explicit selection before their runner images are downloaded.
- **Runner registry**: Runner images are published to GitHub Container Registry using app-versioned tags such as `ghcr.io/<repo>/learnloop-runner-swift:0.1.0` and `ghcr.io/<repo>/learnloop-runner-rust:0.1.0`. The `latest` tag may exist for development convenience, but install/release flows should prefer the app version tag.
- **CI scope**: Pull requests should build and smoke-test only changed runner images where possible. Main/release pipelines should build all runner images and publish versioned tags to GHCR.
- **Offline distribution**: Provide an optional offline full bundle for environments that cannot pull runner images after installation.
- **Uninstalled language behavior**: Swift/Rust practice cards can still be opened, edited, saved, and synced when their runner is not installed. When the user clicks Run, LearnLoop shows a clear "runner not installed" state and an inline `Install runner` action. After installation, the user can run the same practice immediately.
- **Local permission model**: Any logged-in local user can install or remove runner languages. Admin/owner separation is unnecessary for the local install model.

## Open Questions

- None.

## Next Steps

-> `/workflows:plan` for implementation details.
