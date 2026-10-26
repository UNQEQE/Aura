package com.example.eliansitowebonsito;

/**
 * Catálogo de movimientos / poses que dan aura.
 * Para agregar uno nuevo: 1) agrégalo aquí, 2) crea su detector en GestureEngine.
 */
public enum AuraMove {
    //            emoji   título          corto      pts   color         pista                                                  frase (TTS)
    SIX_SEVEN("🤲", "6-7",             "6-7",      100, 0xFFFFD54F, "Manos al pecho, sube una y baja la otra sin parar",   "¡Seis siete!"),
    DAB(      "🤧", "DAB",             "DAB",      150, 0xFF69F0AE, "Un brazo estirado arriba, cara en el codo del otro",   "¡Dab! Mucha aura"),
    FLOSS(    "🧵", "SWISH SWISH",     "Swish",    250, 0xFF40C4FF, "Brazos abajo juntos, balancéalos de lado a lado",      "¡Swish swish!"),
    HYPE(     "🔥", "HYPE",            "Hype",     200, 0xFFFF6E40, "Puños arriba y afuera, bombea alternando rápido",      "¡Hype!"),
    T_POSE(   "✝️", "T-POSE",          "T-Pose",   120, 0xFFB388FF, "Brazos rectos a los lados, a la altura de los hombros", "¡T pose! Dominancia"),
    GIGACHAD( "💪", "GIGACHAD",        "Chad",     180, 0xFFFF5252, "Doble bíceps: codos afuera y puños arriba",           "¡Gigachad!"),
    WAKANDA(  "✖️", "WAKANDA",         "Wakanda",  140, 0xFFEA80FC, "Cruza los brazos en X sobre el pecho",                "¡Wakanda para siempre!"),
    SIUUU(    "🐐", "SIUUU",           "Siuuu",    220, 0xFFFFFFFF, "Brazos abiertos hacia abajo, pecho afuera",           "¡Siuuu!"),
    ALABANZA( "🙌", "ALABANZA",        "Cielo",    200, 0xFFFFAB40, "Ambos brazos estirados arriba en V",                  "¡Aura celestial!"),
    PENSADOR( "🤔", "SIGMA PENSADOR",  "Sigma",     80, 0xFF80CBC4, "Puño bajo el mentón, mirada de sigma",                "Sigma mode activado"),
    CORAZON(  "💖", "CORAZÓN",         "Corazón",  130, 0xFFFF80AB, "Brazos curvos sobre la cabeza, muñecas juntas arriba", "¡Corazón!"),
    SALUDO(   "🎖️", "SALUDO",          "Saludo",    90, 0xFF90A4AE, "Mano en la frente como militar, el otro brazo abajo",  "¡A la orden!"),
    PODER(    "🦸", "POSE DE PODER",   "Poder",    110, 0xFF448AFF, "Manos en la cintura, codos afuera, pecho arriba",      "¡Modo héroe!"),
    ZEN(      "🙏", "ZEN",             "Zen",      100, 0xFF64FFDA, "Palmas juntas frente al pecho, quieto un momento",     "Paz interior"),
    PRESENTE( "✋", "PRESENTE",        "Presente",  90, 0xFFFFEE58, "Un brazo estirado hacia arriba, el otro abajo",        "¡Presente!"),
    GRULLA(   "🥋", "GRULLA",          "Grulla",   240, 0xFFFF8A65, "Levanta una rodilla, como Karate Kid (cuerpo entero)", "¡Estilo grulla!"),
    NI_IDEA(  "🤷", "NI IDEA",         "Ni idea",  100, 0xFFB0BEC5, "Codos pegados, palmas abiertas hacia los lados",       "¿Y yo qué sé?"),
    APLAUSO(  "👏", "APLAUSO",         "Aplauso",  160, 0xFFFFCA28, "Aplaude tres veces frente al pecho",                   "¡Aplausos!");

    public final String emoji;
    public final String title;
    public final String shortName;
    public final int basePoints;
    public final int color;
    public final String hint;
    public final String speech;

    AuraMove(String emoji, String title, String shortName, int basePoints, int color, String hint, String speech) {
        this.emoji = emoji;
        this.title = title;
        this.shortName = shortName;
        this.basePoints = basePoints;
        this.color = color;
        this.hint = hint;
        this.speech = speech;
    }
}
