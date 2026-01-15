/**
 * FishIT-Mapper User Action Tracking Script
 * 
 * This script is injected into web pages to track user interactions
 * and send them back to the Android app via the JavaScriptBridge.
 * 
 * Tracked events:
 * - Clicks on elements
 * - Page scrolling
 * - Form submissions
 * - Input field interactions
 */

(function() {
    'use strict';
    
    // Check if FishITMapper bridge is available
    if (typeof FishITMapper === 'undefined') {
        console.warn('FishITMapper JavaScript bridge not found');
        return;
    }
    
    /**
     * Generate a CSS selector for an element
     * Tries to create the most specific selector possible
     */
    function getSelector(element) {
        if (!element) return 'unknown';
        
        // Prefer ID
        if (element.id) {
            return '#' + element.id;
        }
        
        // Try class name
        if (element.className && typeof element.className === 'string') {
            const classes = element.className.trim().split(/\s+/);
            if (classes.length > 0 && classes[0]) {
                return '.' + classes[0];
            }
        }
        
        // Fall back to tag name
        const tagName = element.tagName ? element.tagName.toLowerCase() : 'unknown';
        
        // Add additional context if available
        const name = element.getAttribute('name');
        if (name) {
            return tagName + '[name="' + name + '"]';
        }
        
        const type = element.getAttribute('type');
        if (type) {
            return tagName + '[type="' + type + '"]';
        }
        
        return tagName;
    }
    
    /**
     * Get safe text content from element (truncated to avoid huge strings)
     */
    function getElementText(element) {
        if (!element) return '';
        const text = element.textContent || element.innerText || '';
        return text.trim().substring(0, 50); // Limit to 50 chars
    }
    
    // Track clicks
    document.addEventListener('click', function(e) {
        try {
            const selector = getSelector(e.target);
            const text = getElementText(e.target);
            const x = Math.round(e.clientX);
            const y = Math.round(e.clientY);
            
            FishITMapper.recordClick(selector, text, x, y);
        } catch (err) {
            console.error('Failed to record click:', err);
        }
    }, true); // Use capture phase to catch all events
    
    // Track scrolling (debounced to avoid too many events)
    let scrollTimeout;
    window.addEventListener('scroll', function() {
        clearTimeout(scrollTimeout);
        scrollTimeout = setTimeout(function() {
            try {
                const scrollX = Math.round(window.scrollX || window.pageXOffset || 0);
                const scrollY = Math.round(window.scrollY || window.pageYOffset || 0);
                
                FishITMapper.recordScroll(scrollX, scrollY);
            } catch (err) {
                console.error('Failed to record scroll:', err);
            }
        }, 150); // Debounce for 150ms
    }, { passive: true });
    
    // Track form submissions
    document.addEventListener('submit', function(e) {
        try {
            const form = e.target;
            if (form && form.tagName === 'FORM') {
                const action = form.action || window.location.href;
                const method = (form.method || 'GET').toUpperCase();
                const formId = form.id || '';
                
                FishITMapper.recordFormSubmit(action, method, formId);
            }
        } catch (err) {
            console.error('Failed to record form submit:', err);
        }
    }, true);
    
    // Track input field interactions (focus, blur, change)
    ['focus', 'blur', 'change'].forEach(function(eventType) {
        document.addEventListener(eventType, function(e) {
            try {
                const target = e.target;
                if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')) {
                    const inputType = target.type || target.tagName.toLowerCase();
                    const inputName = target.name || target.id || '';
                    
                    // Only record if we have some identifying information
                    if (inputName) {
                        FishITMapper.recordInput(inputType, inputName, eventType);
                    }
                }
            } catch (err) {
                console.error('Failed to record input event:', err);
            }
        }, true);
    });
    
    console.log('FishIT-Mapper tracking script initialized');
})();
