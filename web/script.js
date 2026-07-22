document.addEventListener('DOMContentLoaded', () => {
    const gestureItems = document.querySelectorAll('.gesture-item');
    const tp1 = document.querySelector('.tp-1');
    const tp2 = document.querySelector('.tp-2');
    const tp3 = document.querySelector('.tp-3');
    const label = document.querySelector('.demo-label');

    const resetPoints = () => {
        tp1.style.opacity = '0';
        tp2.style.opacity = '0';
        tp3.style.opacity = '0';
        tp1.style.transform = 'translate(-50%, -50%)';
        tp2.style.transform = 'translate(-50%, -50%)';
        tp3.style.transform = 'translate(-50%, -50%)';
    };

    const animateGesture = (type) => {
        resetPoints();
        setTimeout(() => {
            if (type === 'pinch') {
                label.textContent = "Pinch in/out (BPM)";
                tp1.style.opacity = '1';
                tp2.style.opacity = '1';
                tp1.style.top = '30%'; tp1.style.left = '30%';
                tp2.style.top = '70%'; tp2.style.left = '70%';
                
                setTimeout(() => {
                    tp1.style.top = '40%'; tp1.style.left = '40%';
                    tp2.style.top = '60%'; tp2.style.left = '60%';
                }, 500);
            } 
            else if (type === 'drag-1-v') {
                label.textContent = "Vertical Drag (Pitch)";
                tp1.style.opacity = '1';
                tp1.style.top = '70%';
                
                setTimeout(() => {
                    tp1.style.top = '30%';
                }, 500);
            }
            else if (type === 'rotate-2') {
                label.textContent = "2-Finger Rotate (Overlap)";
                tp1.style.opacity = '1';
                tp2.style.opacity = '1';
                tp1.style.top = '50%'; tp1.style.left = '30%';
                tp2.style.top = '50%'; tp2.style.left = '70%';
                
                setTimeout(() => {
                    tp1.style.top = '30%'; tp1.style.left = '50%';
                    tp2.style.top = '70%'; tp2.style.left = '50%';
                }, 500);
            }
            else if (type === 'rotate-3') {
                label.textContent = "3-Finger Rotate (Scrub)";
                tp1.style.opacity = '1';
                tp2.style.opacity = '1';
                tp3.style.opacity = '1';
                
                tp1.style.top = '30%'; tp1.style.left = '50%';
                tp2.style.top = '60%'; tp2.style.left = '30%';
                tp3.style.top = '60%'; tp3.style.left = '70%';
                
                setTimeout(() => {
                    tp1.style.top = '50%'; tp1.style.left = '70%';
                    tp2.style.top = '30%'; tp2.style.left = '40%';
                    tp3.style.top = '70%'; tp3.style.left = '40%';
                }, 500);
            }
        }, 100);
    };

    gestureItems.forEach(item => {
        item.addEventListener('mouseenter', () => {
            gestureItems.forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            animateGesture(item.dataset.gesture);
        });
        
        item.addEventListener('mouseleave', () => {
            item.classList.remove('active');
            resetPoints();
            label.textContent = "Hover a gesture";
        });
    });
});
