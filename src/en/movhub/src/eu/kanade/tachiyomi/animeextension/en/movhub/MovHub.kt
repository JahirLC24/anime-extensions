package eu.kanade.tachiyomi.animeextension.en.movhub

import eu.kanade.tachiyomi.multisrc.yflix.YFlixTheme

class MovHub :
    YFlixTheme(
        "MovHub",
        listOf(
            "1moviesz.to",
            "myflixer.bz",
            "bflix.la",
            "myflixer.fi",
        ),
    ) {

    // TODO: actualizar MovHub cuando se migre al nuevo diseño
    override val moviesSelector = "div.movie-cards div.item"
}
