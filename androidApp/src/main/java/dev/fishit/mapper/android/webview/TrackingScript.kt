package dev.fishit.mapper.android.webview

/**
 * JavaScript tracking script to be injected into web pages.
 * This script captures user interactions and reports them to the native app via the JavaScript bridge.
 */
object TrackingScript {
    
    /**
     * Returns the JavaScript code to inject into web pages for tracking.
     * The script will call methods on the "FishITMapper" JavaScript interface.
     */
    fun getScript(): String = """
        (function() {
            // Avoid double-initialization
            if (window.__FISHIT_MAPPER_INITIALIZED__) return;
            window.__FISHIT_MAPPER_INITIALIZED__ = true;
            
            console.log('[FishIT-Mapper] Tracking initialized');
            
            // Helper function to get a CSS selector for an element
            function getSelector(element) {
                if (!element) return 'unknown';
                
                // Prefer ID
                if (element.id) return '#' + element.id;
                
                // Then class name
                if (element.className && typeof element.className === 'string') {
                    const firstClass = element.className.split(' ')[0];
                    if (firstClass) return '.' + firstClass;
                }
                
                // Fallback to tag name
                return element.tagName.toLowerCase();
            }
            
            // Helper function to get element text (truncated)
            function getElementText(element) {
                if (!element) return '';
                const text = element.textContent || element.innerText || element.value || '';
                return text.trim().substring(0, 100);
            }
            
            // Track clicks
            document.addEventListener('click', function(e) {
                try {
                    const target = e.target;
                    const selector = getSelector(target);
                    const text = getElementText(target);
                    const x = Math.round(e.clientX);
                    const y = Math.round(e.clientY);
                    
                    if (window.FishITMapper && window.FishITMapper.recordClick) {
                        window.FishITMapper.recordClick(selector, text, x, y);
                    }
                } catch (err) {
                    console.error('[FishIT-Mapper] Click tracking error:', err);
                }
            }, true);
            
            // Track scrolls (debounced to avoid too many events)
            let scrollTimeout;
            const scrollDebounceMs = 200;
            window.addEventListener('scroll', function() {
                clearTimeout(scrollTimeout);
                scrollTimeout = setTimeout(function() {
                    try {
                        const scrollY = Math.round(window.scrollY || window.pageYOffset || 0);
                        const scrollX = Math.round(window.scrollX || window.pageXOffset || 0);
                        
                        if (window.FishITMapper && window.FishITMapper.recordScroll) {
                            window.FishITMapper.recordScroll(scrollY, scrollX);
                        }
                    } catch (err) {
                        console.error('[FishIT-Mapper] Scroll tracking error:', err);
                    }
                }, scrollDebounceMs);
            }, true);
            
            // Track form submits
            document.addEventListener('submit', function(e) {
                try {
                    const form = e.target;
                    const formAction = form.action || window.location.href;
                    const formMethod = (form.method || 'GET').toUpperCase();
                    
                    // Collect field names (NOT values for privacy) as JSON array
                    const inputs = form.querySelectorAll('input, select, textarea');
                    const fieldNamesArray = Array.from(inputs)
                        .map(function(input) { return input.name || input.id || 'unnamed'; })
                        .filter(function(name) { return name !== 'unnamed'; });
                    const fieldNames = JSON.stringify(fieldNamesArray);
                    
                    if (window.FishITMapper && window.FishITMapper.recordFormSubmit) {
                        window.FishITMapper.recordFormSubmit(formAction, formMethod, fieldNames);
                    }
                } catch (err) {
                    console.error('[FishIT-Mapper] Form submit tracking error:', err);
                }
            }, true);
            
            // Track field changes (debounced with memory management)
            let fieldChangeTimeouts = new Map();
            const fieldChangeDebounceMs = 500;
            const MAX_TRACKED_FIELDS = 100; // Limit to prevent unbounded growth
            
            // Generate unique key for field tracking
            function getFieldKey(field) {
                return (field.name || '') + '_' + (field.id || '') + '_' + (field.type || '');
            }
            
            // Cleanup old timeouts periodically
            function cleanupFieldTimeouts() {
                if (fieldChangeTimeouts.size > MAX_TRACKED_FIELDS) {
                    // Remove half of tracked fields in Map iteration order
                    const toRemove = Math.floor(fieldChangeTimeouts.size / 2);
                    let removed = 0;
                    for (let key of fieldChangeTimeouts.keys()) {
                        clearTimeout(fieldChangeTimeouts.get(key));
                        fieldChangeTimeouts.delete(key);
                        removed++;
                        if (removed >= toRemove) break;
                    }
                }
            }
            
            document.addEventListener('input', function(e) {
                try {
                    const field = e.target;
                    if (field.tagName === 'INPUT' || field.tagName === 'TEXTAREA' || field.tagName === 'SELECT') {
                        const fieldName = field.name || field.id || field.type || 'unnamed';
                        const fieldType = field.type || 'text';
                        const fieldKey = getFieldKey(field);
                        
                        // Cleanup if needed
                        if (fieldChangeTimeouts.size >= MAX_TRACKED_FIELDS) {
                            cleanupFieldTimeouts();
                        }
                        
                        // Debounce per field
                        clearTimeout(fieldChangeTimeouts.get(fieldKey));
                        fieldChangeTimeouts.set(fieldKey, setTimeout(function() {
                            if (window.FishITMapper && window.FishITMapper.recordFieldChange) {
                                window.FishITMapper.recordFieldChange(fieldName, fieldType);
                            }
                            // Remove timeout after firing
                            fieldChangeTimeouts.delete(fieldKey);
                        }, fieldChangeDebounceMs));
                    }
                } catch (err) {
                    console.error('[FishIT-Mapper] Field change tracking error:', err);
                }
            }, true);
            
            console.log('[FishIT-Mapper] All event listeners registered');
        })();
    """.trimIndent()
}
