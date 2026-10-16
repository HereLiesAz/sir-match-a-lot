document.addEventListener('DOMContentLoaded', () => {
    const gestureItems = document.querySelectorAll('.gesture-item');
    const tp1 = document.querySelector('.tp-1');
    const tp2 = document.querySelector('.tp-2');
    const tp3 = document.querySelector('.tp-3');
    const label = document.querySelector('.demo-label');

    // The demo is decoration: if its markup is missing, skip it rather than
    // throw and take the shared-session reader down with it.
    const points = [tp1, tp2, tp3].filter(Boolean);
    if (!label || points.length === 0) return;
    const IDLE_LABEL = 'Hover, focus or tap a gesture';
    label.textContent = IDLE_LABEL;
    label.setAttribute('aria-live', 'polite');

    const resetPoints = () => {
        points.forEach((p) => {
            p.style.opacity = '0';
            p.style.transform = 'translate(-50%, -50%)';
        });
    };

    /**
     * Each entry describes one gesture as a caption plus a start and end pose.
     * Poses are [top, left] percentages, one per finger, so the number of
     * fingers shown always matches the gesture being described.
     *
     * Keys match the data-gesture attributes in index.html. These were
     * previously out of step with the app: the page still animated the
     * superseded mapping, in which pinch changed BPM and 2-finger rotate
     * shifted track overlap.
     */
    const GESTURES = {
        'drag-1': {
            caption: '1 Finger — manipulate the clips',
            from: [['55%', '35%']],
            to: [['45%', '65%']],
        },
        'drag-2-h': {
            caption: '2-Finger Horizontal — crossfader',
            from: [['40%', '30%'], ['60%', '30%']],
            to: [['40%', '70%'], ['60%', '70%']],
        },
        'drag-2-v': {
            caption: '2-Finger Vertical — smart scratch, into reverse',
            from: [['30%', '40%'], ['30%', '60%']],
            to: [['70%', '40%'], ['70%', '60%']],
        },
        'rotate-2': {
            caption: '2-Finger Rotate — master volume',
            from: [['50%', '30%'], ['50%', '70%']],
            to: [['30%', '50%'], ['70%', '50%']],
        },
        'pinch-2': {
            caption: '2-Finger Pinch — bass boost',
            from: [['30%', '30%'], ['70%', '70%']],
            to: [['45%', '45%'], ['55%', '55%']],
        },
        three: {
            caption: '3 Fingers — move, zoom and rotate the platter',
            from: [['30%', '50%'], ['62%', '32%'], ['62%', '68%']],
            to: [['50%', '68%'], ['32%', '38%'], ['70%', '38%']],
        },
    };

    const pose = (frames) => {
        points.forEach((point, index) => {
            const frame = frames[index];
            if (!frame) {
                point.style.opacity = '0';
                return;
            }
            point.style.opacity = '1';
            point.style.top = frame[0];
            point.style.left = frame[1];
        });
    };

    let pending = [];

    const clearPending = () => {
        pending.forEach(clearTimeout);
        pending = [];
    };

    const animateGesture = (type) => {
        clearPending();
        resetPoints();

        const gesture = GESTURES[type];
        if (!gesture) return;

        label.textContent = gesture.caption;
        pending.push(setTimeout(() => pose(gesture.from), 100));
        pending.push(setTimeout(() => pose(gesture.to), 600));
        // Return to the start so hovering shows the motion repeatedly.
        pending.push(setTimeout(() => pose(gesture.from), 1400));
        pending.push(setTimeout(() => pose(gesture.to), 1900));
    };

    const deactivate = (item) => {
        item.classList.remove('active');
        item.setAttribute('aria-pressed', 'false');
        clearPending();
        resetPoints();
        label.textContent = IDLE_LABEL;
    };

    const activate = (item) => {
        gestureItems.forEach((other) => {
            other.classList.remove('active');
            other.setAttribute('aria-pressed', 'false');
        });
        item.classList.add('active');
        item.setAttribute('aria-pressed', 'true');
        animateGesture(item.dataset.gesture);
    };

    gestureItems.forEach((item) => {
        // Keyboard and screen-reader users reach the demo the same way a
        // mouse does: each item is a focusable toggle.
        item.setAttribute('tabindex', '0');
        item.setAttribute('role', 'button');
        item.setAttribute('aria-pressed', 'false');

        item.addEventListener('mouseenter', () => activate(item));
        item.addEventListener('mouseleave', () => deactivate(item));
        item.addEventListener('focus', () => activate(item));
        item.addEventListener('blur', () => deactivate(item));
        // Touch screens have no hover: a tap plays the demo (and tapping
        // elsewhere blurs the item, which stops it).
        item.addEventListener('click', () => activate(item));
        item.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                activate(item);
            } else if (event.key === 'Escape') {
                deactivate(item);
            }
        });
    });
});

/**
 * Reads a shared session out of the URL.
 *
 * The app builds these links (`SessionLink.toUrl`) and this page is their
 * default destination, so without this a copied link lands on a page that
 * ignores it. The format is deliberately legible query parameters — see
 * `docs/API.md` — which is exactly what makes reading it here twenty lines
 * rather than a parser.
 *
 * Runs separately from the gesture animation above: a malformed link must not
 * take the rest of the page down with it.
 */
document.addEventListener('DOMContentLoaded', () => {
    const section = document.getElementById('shared-session');
    if (!section) return;

    // Bounds on what a link can make this page render. A link is untrusted
    // input, so every value is capped before it reaches the DOM.
    const MAX_TRACKS = 50;
    const MAX_TEXT = 200;
    const MAX_CUES = 4;
    const clip = (text, max = MAX_TEXT) =>
        text.length > max ? `${text.slice(0, max - 1)}…` : text;
    const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

    const params = new URLSearchParams(window.location.search);
    const deckA = params.getAll('a').slice(0, MAX_TRACKS).map((t) => clip(t));
    const deckB = params.getAll('b').slice(0, MAX_TRACKS).map((t) => clip(t));
    if (deckA.length === 0 && deckB.length === 0) return;

    const fill = (id, tracks) => {
        const list = document.getElementById(id);
        if (!list) return;
        list.innerHTML = '';
        if (tracks.length === 0) {
            const empty = document.createElement('li');
            empty.className = 'session-empty';
            empty.textContent = 'Empty';
            list.appendChild(empty);
            return;
        }
        tracks.forEach((track) => {
            const item = document.createElement('li');
            // textContent, not innerHTML: a track title arrives from whoever
            // wrote the link, and a link is a thing strangers send you.
            item.textContent = track;
            list.appendChild(item);
        });
    };

    const cues = (id, value) => {
        const node = document.getElementById(id);
        if (!node) return;
        const points = (value || '')
            .split(',')
            .map((cue) => cue.trim())
            .filter((cue) => cue.length > 0 && cue.length <= 12 && Number.isFinite(Number(cue)))
            .slice(0, MAX_CUES);
        node.textContent = points.length ? `Cues: ${points.join('s, ')}s` : '';
    };

    fill('session-deck-a', deckA);
    fill('session-deck-b', deckB);
    cues('session-cues-a', params.get('ca'));
    cues('session-cues-b', params.get('cb'));

    const meta = document.getElementById('session-meta');
    if (meta) {
        const parts = [];
        const bpm = params.get('bpm');
        // Same plausible-tempo range the app accepts; anything outside is dropped.
        if (bpm && Number.isFinite(Number(bpm)) && Number(bpm) >= 20 && Number(bpm) <= 300) {
            parts.push(`${Number(bpm).toFixed(1)} BPM`);
        }
        const key = params.get('key');
        if (key) parts.push(`Key ${clip(key, 8)}`);
        const crossfade = params.get('x');
        if (crossfade && Number.isFinite(Number(crossfade))) {
            // SessionLink writes the crossfader as an integer in -100..100.
            const value = clamp(Math.round(Number(crossfade)), -100, 100);
            const towards = value === 0 ? 'centred' : value < 0 ? 'toward A' : 'toward B';
            parts.push(`Crossfader ${value} (${towards})`);
        }
        const room = params.get('room');
        if (room) parts.push(`Room ${clip(room, 12).toUpperCase()}`);
        meta.textContent = parts.join('  ·  ');
    }

    section.hidden = false;
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
});
