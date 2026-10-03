package com.cyclone.mobile.mind.decide

/**
 * The phone model's built-in examples (alpha 89), in English and Dutch: the everyday ways people ask for each Instant
 * action, including phrasings the strict grammar doesn't take, and the requests that must never be instant (writing,
 * questions, several steps, talk not meant for Cyclone). "thing" stands for the app or on-screen label a request names;
 * the model learns the shape, the phone's candidates supply the name. Lessons taught by JEV are added on top.
 */
object PhoneModelSeeds {
    private fun instant(intent: String, vararg texts: String) = texts.map { PhoneModel.Example(it, "instant", intent) }
    private fun mode(mode: String, vararg texts: String) = texts.map { PhoneModel.Example(it, mode, "none") }

    val EXAMPLES: List<PhoneModel.Example> = listOf(
        instant("swipe_up", "swipe up", "swipe it up", "flick up", "slide up", "veeg omhoog", "veeg naar boven", "swipe naar boven"),
        instant("swipe_down", "swipe down", "pull down", "slide down", "flick down", "veeg omlaag", "veeg naar beneden", "trek naar beneden"),
        instant("swipe_left", "swipe left", "slide left", "next page", "go to the next one", "veeg naar links", "veeg links"),
        instant("swipe_right", "swipe right", "slide right", "previous page", "go back one page", "veeg naar rechts", "veeg rechts"),
        instant("scroll_up", "scroll up", "go up a bit", "move up", "a bit higher", "up a little", "scrol omhoog", "iets omhoog", "naar boven scrollen"),
        instant("scroll_down", "scroll down", "go down a bit", "move down", "show me more", "keep scrolling", "a bit lower", "scrol omlaag",
            "iets naar beneden", "verder naar beneden", "scroll verder"),
        instant("back", "go back", "back", "previous screen", "take me back", "undo that screen", "ga terug", "terug", "vorige scherm"),
        instant("home", "go home", "home screen", "take me home", "close this and go home", "go to my home screen", "naar het startscherm",
            "startscherm", "ga naar huis scherm"),
        instant("recents", "recent apps", "show my open apps", "app switcher", "switch apps", "recente apps", "open apps laten zien"),
        instant("tap", "tap thing", "click thing", "press thing", "hit thing", "select thing", "tap on thing", "click on the thing button",
            "klik op thing", "druk op thing", "tik op thing", "kies thing"),
        instant("open_app", "open thing", "launch thing", "start thing", "open up thing", "go to thing", "bring up thing", "pull up thing",
            "show me thing", "open thing app", "open de thing app", "start thing op", "ga naar thing", "open mijn thing",
            "i want to watch thing", "i want to use thing", "let me see thing", "take me to thing", "ik wil thing"),
        instant("camera", "open the camera", "camera", "open camera", "start the camera", "camera openen", "open de camera", "start camera"),
        instant("photo", "take a photo", "take a picture", "snap a pic", "shoot a photo", "maak een foto", "neem een foto", "foto maken"),
        instant("selfie", "take a selfie", "picture of me", "take a picture of me", "selfie please", "maak een selfie", "foto van mij",
            "maak een foto van mezelf"),
        instant("flashlight_on", "turn on the flashlight", "flashlight on", "torch on", "put the light on", "light please", "i need light",
            "zaklamp aan", "zet de zaklamp aan", "lampje aan"),
        instant("flashlight_off", "turn off the flashlight", "flashlight off", "torch off", "light off", "kill the light", "zaklamp uit",
            "zet de zaklamp uit", "lampje uit"),
        instant("volume_up", "volume up", "louder", "turn it up", "make it louder", "i can't hear it", "harder", "volume omhoog", "zet het harder",
            "turn the sound up a notch", "a bit louder", "raise the volume", "turn up the sound", "geluid harder"),
        instant("volume_down", "volume down", "quieter", "turn it down", "make it quieter", "too loud", "zachter", "volume omlaag", "zet het zachter",
            "turn the sound down a notch", "a bit quieter", "lower the volume", "turn down the sound", "geluid zachter"),
        instant("media_play_pause", "pause", "play", "pause the music", "resume the music", "stop the song", "pauze", "afspelen", "muziek pauzeren"),
        instant("media_next", "next song", "skip this", "skip song", "next track", "volgende nummer", "sla over", "volgende liedje"),

        mode("flash", "open settings and turn on wifi", "go to settings then bluetooth", "open thing and play my liked songs",
            "turn on dark mode", "find the battery settings", "turn on do not disturb", "connect to my headphones",
            "zet wifi aan", "zet donkere modus aan", "open instellingen en zet bluetooth aan", "zoek de batterij instellingen"),
        mode("mind", "text mom i'm running late", "send a message to tom", "reply to the last email", "what's the weather tomorrow",
            "how far is the airport", "find a cheap flight to paris", "order my usual pizza", "book a table for two tonight",
            "write a post about my trip", "why is my phone slow", "summarize this page", "delete these photos", "pay the bill",
            "stuur mam dat ik later ben", "wat voor weer wordt het morgen", "bestel mijn pizza", "schrijf een bericht naar tom",
            "hoe ver is het vliegveld", "zoek een goedkope vlucht naar parijs", "verwijder deze foto's", "betaal de rekening"),
        mode("ignore", "no i told him yesterday", "yeah that's fine", "hold on a second", "thanks", "never mind", "okay cool", "hmm",
            "nee dat zei ik gisteren", "ja prima", "wacht even", "dank je", "laat maar", "oke"),
    ).flatten()
}
