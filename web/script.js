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
