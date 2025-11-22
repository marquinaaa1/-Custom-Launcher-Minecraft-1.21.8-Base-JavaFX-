function minimize() {
    if (window.javaApp) {
        try {
            window.javaApp.minimize();
        } catch (e) {
            showStatus('Ошибка минимизации окна: ' + e.message, 'error');
        }
    } else {
        showStatus('Ошибка: нет связи с Java для минимизации', 'error');
    }
}

function closeLauncher() {
    if (window.javaApp) {
        try {
            window.javaApp.close();
        } catch (e) {
            showStatus('Ошибка закрытия окна: ' + e.message, 'error');
        }
    } else {
        showStatus('Ошибка: нет связи с Java для закрытия', 'error');
    }
}

function toggleMenu() {
    const sidebar = document.querySelector('.sidebar');
    sidebar.classList.toggle('active');
}

function showLoadingScreen() {
    document.getElementById('loadingScreen').classList.add('active');
    console.log("JavaScript: showLoadingScreen() called, 'active' class added.");
}

function hideLoadingScreen() {
    console.log("JavaScript: hideLoadingScreen() called. Attempting to hide loading screen...");
    setTimeout(() => {
        document.getElementById('loadingScreen').classList.remove('active');
        console.log("JavaScript: Loading screen hidden after 3-second delay."); 
    }, 3000); 
}

function updateLoadingProgress(progress, message) {
    console.log(`JavaScript: updateLoadingProgress() called with progress: ${progress}, message: '${message}'`);
    const progressBarFill = document.getElementById('progressBarFill');
    const progressBarPercentage = document.getElementById('progressBarPercentage');
    const loadingMessage = document.getElementById('loadingMessage');

    console.log(`JavaScript: progressBarFill found: ${!!progressBarFill}, id: ${progressBarFill ? progressBarFill.id : 'N/A'}`); 
    console.log(`JavaScript: progressBarPercentage found: ${!!progressBarPercentage}, id: ${progressBarPercentage ? progressBarPercentage.id : 'N/A'}`); // Добавляем ID
    console.log(`JavaScript: loadingMessage found: ${!!loadingMessage}, id: ${loadingMessage ? loadingMessage.id : 'N/A'}`); 

    if (progressBarFill) {
        progressBarFill.style.width = `${progress}%`;
        console.log(`JavaScript: progressBarFill.style.width set to ${progress}%`);
    }
    if (progressBarPercentage) {
        progressBarPercentage.textContent = `${progress}%`;
        console.log(`JavaScript: progressBarPercentage.textContent set to ${progress}%`);
    }
    if (loadingMessage) {
        loadingMessage.textContent = message;
        console.log(`JavaScript: loadingMessage.textContent set to '${message}'`);
    }
}

function openShop() {
    const shopUrl = "https://cherry.pizza/shop";
    if (window.javaApp && window.javaApp.openURL) {
        window.javaApp.openURL(shopUrl);
    } else {
        showStatus('Ошибка: не удается открыть магазин. Нет связи с Java.', 'error');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const sidebarCloseBtn = document.querySelector('.sidebar-close-btn');
    if (sidebarCloseBtn) {
        sidebarCloseBtn.addEventListener('click', toggleMenu);
    }
});

function openSettings() {
    showStatus('Настройки в разработке', 'loading');
}

function launchGame() {
    console.log("JavaScript: launchGame() function entered.");
    const username = document.getElementById('displayUsername').textContent;
    const playButton = document.querySelector('.btn-play');     

    if (!username || username === 'marquina') {
    }
    
    playButton.disabled = true;

    showLoadingScreen(); 
    console.log("JavaScript: launchGame() called.");

    if (window.javaApp) {
        window.javaApp.launch(username); 
        console.log("JavaScript: Call to window.javaApp.launch() executed.");
    } else {
        showStatus('Ошибка: нет связи с Java', 'error');
        playButton.disabled = false; 
    }
    console.log("JavaScript: End of launchGame() function.");
}

function showStatus(message, type = 'loading') {
    const statusDiv = document.getElementById('status');
    statusDiv.textContent = message;
    statusDiv.className = 'status show ' + type;
    
    if (type === 'success' || type === 'error') {
        setTimeout(() => {
            statusDiv.classList.remove('show');
        }, 3000);
    }
}

function updateProgress(message, progress) {
    showStatus(`${message} (${progress}%)`, 'loading');
}

let isDragging = false;
let lastMouseX = 0;
let lastMouseY = 0;

document.addEventListener('mousedown', (e) => {
    if (e.target.closest('button') || e.target.closest('input') || e.target.closest('a')) {
        return;
    }
    
    if (!window.javaApp) {
        console.log('javaApp bridge not available for dragging.');
        showStatus('Ошибка: нет связи с Java для перемещения окна', 'error');
        return;
    }

    isDragging = true;
    lastMouseX = e.screenX;
    lastMouseY = e.screenY;

    e.preventDefault();
});

document.addEventListener('mousemove', (e) => {
    if (isDragging) {
        const deltaX = e.screenX - lastMouseX;
        const deltaY = e.screenY - lastMouseY;

        window.javaApp.moveWindow(deltaX, deltaY);

        lastMouseX = e.screenX;
        lastMouseY = e.screenY;
    }
});

document.addEventListener('mouseup', () => {
    isDragging = false;
});

function checkJavaBridge() {
    if (window.javaApp) {
        return true;
    } else {
        setTimeout(checkJavaBridge, 100);
        return false;
    }
}

window.addEventListener('load', () => {
    console.log('Page loaded, checking Java bridge...');
    checkJavaBridge();
});

checkJavaBridge();
