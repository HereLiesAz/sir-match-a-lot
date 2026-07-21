# Session Prompts

Here is the archive of all user prompts submitted during this development session:


Add a dropdown filter in the library section to allow users to instantly sort tracks by 'Camelot Key Proximity' relative to the currently loaded Deck A track.

You have sync beats, let's also have auto pitch and auto stretch. I'd also like a sampler pad that I can record and replay to, and I want that to automatically grab samples to fill up the unused pads. Then, I'd like to be able to visualize the whole thing with an energy graph that utilizes bpm and pitch, and that I can reshape with one and two finger gestures.

Implement a 'Shuffle Crate' button that intelligently selects tracks from the current library to fill Deck A and Deck B based on high harmonic compatibility scores and BPM matching, ensuring a seamless start to a mix session.

Implement the Automatchic Mix, which utilizes ALL of the app's tools to automatically bring a pro-grade remix playlist.


Refactor the sampler pad to mirror a kaospad's or Kitara's functionality.

Add to that an automatic loop maker, which samples loops from the active playlist's songs. Also, add quick cue markers so we can see what we're doing. If possible, also tag other elements of possible interest in the song. Then, expose this app's complete api.

 I'd like to be able to link multiple devices so I can have a different app screen open on each screen and they're all working together as if they were all one device.

Implement a feature that exports the currently loaded 'training session' (two tracks, their cue points, and loop settings) as a shareable link using query parameters.

> I want to visualize the songs that are queued up as waveforms that stick out of a circle, color coded for energy, allowing me to use one and two finger gestures to control various factors all in one place. Pinch to zoom slows down or speeds up the bpm. single finger drag vertically adjusts pitch. single finger horizontal adjusts bass/treble. two finger rotation adjusts overlap. two finger up and down adjusts crossfade. two finger horizontal is rewind / fast-forward. three finger rotation spins the circle around. three finger pinch adjusts volume. And the playhead should be visible and moving around the circle like a hand on a stopwatch.

Of course we need to support local audio files. We should be able to support any music service that you can grab a link for a song or playlist. For multi-device, we should be able to one-click auto connect while the devices are on the same wifi network. 

Allow for two axis gestures, performing two functions at once. 

I'm even thinking that we should run the outside of the circle as deck A and the inside of the circle as deck B

the sampler/looper should be able to be placed on a deck space, too, displaying a however many loops the same way it displays a song. This allows the user to put as many loops on a deck as they'd like, alongside however many songs they want queued on that deck

concentric rings? No, there are only two decks. Deck A's waveforms protrude outward from the circle's outline, and Deck B's waveforms protrude inward from the circle's outline. 

Actually, double tapping should select both Deck A and Deck B's waveforms in that spot. Single tap selects only the waveform in that place on deck A or on deck B

Just to make sure we're on the same page, if the user adds a single sample to a deck and only plays that deck, that sample is now looping, with only its waveform circumventing the circle.
