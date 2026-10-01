// agent-skills — Working rules and readback skills for AI agents, by rank.
//
// Card: agent-skills/AGENTS.md   Registry: modules.toml [module.agent_skills]
// Owns: the six rank skill files, their pinned hashes, the install guide.
// Depends on: nothing (leaf)
//
// Toolchain versions come from gradle/libs.versions.toml.

// Registered module with no sources: it holds instruction files for AI agents,
// nothing that compiles. The build applies the base plugin and stops there.

plugins {
    base
}
