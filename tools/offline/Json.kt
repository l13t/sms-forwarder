// Domain tests exercise no JSON operations; production uses kotlinx.serialization.
package kotlinx.serialization.json
class Json(block: Builder.() -> Unit) {
    class Builder { var encodeDefaults=false; var explicitNulls=false; var ignoreUnknownKeys=false }
    init { Builder().block() }
}
