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

when a track or tracks are selected, long pressing on that track removes it/them

As the user performs a gesture, I want what the user is doing to appear onscreen for as long as they're doing it, and dissolve away as soon as they stop. because multiple gestures are possible at once, and because gestures may move fast, the text for the first gesture should favor appearing at 12:00 (the top). The second gesture appears at 1:30, the third at 10:30, the fourth at 3, fifth at 9, etc. And a different text can only take one of those spots again once the previous text completes its dissolve.  

4:30, 7:30, and 6:00 are also priority clock spots, with 6:00 being the least tended toward

If a gesture's text is fading and that gesture is performed again before the dissolve is complete, it retakes that spot

I want you to create comprehensive documentation and a webpage for this app. 

The screen with the decks is totally unnecessary. move the play, sync, harmonize and other controls to the circle's screen. Also, the energy graph should be displayed around the circle, repeating as necessary. Adjustments apply to both tracks. And then, take the circle OUT of the card it's in. It needs to be the FEATURE of the app. Change the layout of that screen so that in everything is in the same place whether portrait or landscape. the list of songs should be at the bottom of the screen in portrait, scrolling horizontally. The navigation bar should remain in place. 

Yes. And the gestures should be more global, I shouldn't have to perform them inside the circle. 

Library stays. Turn the decks tab into the play/pause button. 

We don't need the A/B buttons on the songs. Nor the "handle. 

no, you aren't hearing me. it shouldn't matter where I perform a gesture, unless I'm interacting with audio clips. Currently my gestures aren't applied as soon as they exit the circle

I want the waveforms to work and look a bit more like a mix of these: https://youtu.be/9CTP5o_Laco?si=01BPkRbNvIi5PYtp  https://www.vecteezy.com/video/73168202-circle-audio-spectrum-sound-wave and the image

The waveform needs to glow and itsintensity brighten with the music. It should be a visualizer just as much as it is a tool.

the gesture tags should only be the text. You have them in rounded boxes. And currently, they aren't dissolving. Should happen as soon as the gesture ends

No, people will perform one gesture and continue into another gesture. You need to make the gesture end when the conditions that define that gesture in the first place aren't being met. 

Also, x,y gestures should be allowed, meaning it's possible to perform two gestures at the same time. 

Oh! Also, I want the gesture tags to float upwards as soon as they appear and continue until they're  gone--offscreen or dissolved. This makes room for other gestures so we don't ever run up against a backlog, and it'll have the same feel as gaining points in a video game. 

I want the background to be a music visualizer, like an out of focus light show at a crazy EDM rave emphasizing and coordinated with the music.  

And I want the playhead to NOT continue to the center of the circle. I want it to be a slash of red light that is always twice the width of both decks' waveforms combined. It should definitely be glowing. All waveforms around the circle should be glowing, brightness and size intensifying and growing or dimming and shrinking with the current audio output. 

We are changing gestures. three fingers will be for placing, resizing, and rotating the entire circle. This way, users can zoom in on parts of a song and do more precise work. Two fingers horizontal is the crossfader from deck A to deck B. Two fingers vertical is seek forward or backward, allowing for scratches. The thing is, two fingers vertical ALSO allows for pitch change AND BPM speed. Wouldn't work if this was a real record, but it's not, so it can be a SMART turntable. Backward (effect on a non-linear curve) causes the bpm to slow, causing the pitch to change, and the speed decreases until it gets to zero, and starts to play backwards. And then, two finger rotate is now volume, which will feel like turning a knob. Lastly two finger pinch/spread turns up or down BASS BOOST! And with this rearrangement of gesture functions, all single finger gestures are about manipulating the sound clips themselves. 

OH! I have an (ironically named) easter egg I want to add. If the user attempts to go backward TOO far, drawing out the scratch, I want an exceptionally low, growling voice say (slowly at first, increasing in speed) "I am Satan, Lord of Darkness."

You have the playhead at a constant size. That's wrong. It should be twice as long as the CURRENT combined waveform height. So its length should bounce as it passes the hills and valleys of the waveforms as it passes them. Also. you have a circle in the center of the circle. I HATE that. I actually don't like either circle. neither should exist. the only indication that ONE of them exists are the waveforms themselves. it should be invisible otherwise. 

when there are no waveforms, the playhead should just be a glowing red dot. 

1) you have the circle decks stuck to a dark gray square. You also have the focused track text stuck to it.  2) The "waveforms" are just zigzags that have nothing to do with the audio's actual waveform.  It is utterly featureless and completely unfaithful to the actual waveform. I've attached an image of what the waveform SHOULD look like (Not with a circle inside, but DEFINITELY with the waveform's size very exaggerated.) 3) The waveform barely jiggles in size. I want to see it CAREENING outward, and deck B's reaching out to itself on the other side. 4) I can't drag the songs at the bottom at all. 5) I imported a playlist, and it treated it as one single song. Importing a playlist should import all the songs in that playlist. 6) Get rid of ALL of the built-in audio clips. We will be using the Azphalt store to allow the users to download sample packs and sound effects. So incorporate the store https://azphalt.org  7) The app should be working with the ENTIRE songs that it imports. 8) Trim any silence at the beginning and end of every song.  9) your lightshow visualizer in the background is nearly a strobe light it's so spastic. And it doesn't appear to synchronize with the music at all

No. If the playlist is longer, then set it up to run in the background, allowing it to pause and resume, reporting its progress in a notification

