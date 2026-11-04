package app.deskling.data

/**
 * Which pet: each dial firmware is built for one (it says which in hello and state). Its animations live in
 * assets/pets/<id>/. Without a paired dial the app's own choice applies.
 */
enum class Pet(val id: String, val title: String) {
    WISP("wisp", "Wisp"), CRUMB("crumb", "Crumb");

    companion object {
        fun of(id: String?): Pet? = entries.firstOrNull { it.id == id }
    }
}
