package dev.breaker.server.syncapi.accounts

/** The outcome of setting one account's admin role. */
internal sealed class UpdateRoleResult {
    /** The account has the asked role now (or had it already). [username] is the stored name. */
    class Updated(val username: String, val role: Role) : UpdateRoleResult()

    /** No account has this name. */
    object NoSuchAccount : UpdateRoleResult()

    /** The demotion would leave no admin. */
    object LastAdmin : UpdateRoleResult()
}
