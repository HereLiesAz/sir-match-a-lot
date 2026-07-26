document.addEventListener('DOMContentLoaded', () => {
    const gestureItems = document.querySelectorAll('.gesture-item');
    const tp1 = document.querySelector('.tp-1');
    const tp2 = document.querySelector('.tp-2');
    const tp3 = document.querySelector('.tp-3');
    const label = document.querySelector('.demo-label');

    const points = [tp1, tp2, tp3];

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

    gestureItems.forEach((item) => {
        item.addEventListener('mouseenter', () => {
            gestureItems.forEach((other) => other.classList.remove('active'));
            item.classList.add('active');
            animateGesture(item.dataset.gesture);
        });

        item.addEventListener('mouseleave', () => {
            item.classList.remove('active');
            clearPending();
            resetPoints();
            label.textContent = 'Hover a gesture';
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

    const params = new URLSearchParams(window.location.search);
    const deckA = params.getAll('a');
    const deckB = params.getAll('b');
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
            .filter((cue) => cue.length > 0 && !Number.isNaN(Number(cue)));
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
        if (bpm && !Number.isNaN(Number(bpm))) parts.push(`${Number(bpm).toFixed(1)} BPM`);
        const key = params.get('key');
        if (key) parts.push(`Key ${key}`);
        const crossfade = params.get('x');
        if (crossfade && !Number.isNaN(Number(crossfade))) {
            const value = Number(crossfade);
            const towards = value === 0 ? 'centred' : value < 0 ? 'toward A' : 'toward B';
            parts.push(`Crossfader ${value} (${towards})`);
        }
        const room = params.get('room');
        if (room) parts.push(`Room ${room.toUpperCase()}`);
        meta.textContent = parts.join('  ·  ');
    }

    section.hidden = false;
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
});
