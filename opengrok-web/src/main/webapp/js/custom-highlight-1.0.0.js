/**
 * Custom Highlight Extension for OpenGrok
 * 自定义代码高亮扩展插件
 * 
 * 支持通过CSV文件定义高亮规则
 * CSV格式：文件路径,行号,开始列,结束列,颜色,注释
 * 例如：src/main.c,10,15,25,1,重要变量
 */
(function (window, document, $) {
    'use strict';

    const customHighlight = function () {
        return {
            // 默认配置
            defaults: {
                enabled: true,
                csvUrl: null, // CSV 文件的 URL
                contextPath: window.contextPath || '',
                highlightClass: 'custom-highlight',
                tooltipClass: 'custom-highlight-tooltip'
            },

            // 高亮规则数据
            highlightRules: [],
            options: {},
            initialized: false,

            /**
             * 初始化插件
             */
            init: function (options) {
                this.options = $.extend({}, this.defaults, options || {});
                
                if (!this.options.enabled) {
                    console.log('Custom highlight plugin is disabled');
                    return;
                }

                this.initialized = true;
                this.bindEvents();
                
                // 如果指定了CSV URL，自动加载
                if (this.options.csvUrl) {
                    console.log('Loading highlight rules from:', this.options.csvUrl);
                    this.loadHighlightRules(this.options.csvUrl);
                }
                
                console.log('Custom highlight plugin initialized');
                return this;
            },

            /**
             * 绑定事件
             */
            bindEvents: function () {
                const self = this;
                
                // 绑定键盘快捷键
                $(document).keypress(function (e) {
                    if (self.isTextInputFocused()) {
                        return true;
                    }
                    
                    const key = e.which;
                    switch (key) {
                        case 67: // 'C' - 加载自定义高亮
                            self.promptForCsvUrl();
                            break;
                        case 99: // 'c' - 清除自定义高亮
                            self.clearAllHighlights();
                            break;
                    }
                    return true;
                });
            },

            /**
             * 检查是否有文本输入框获得焦点
             */
            isTextInputFocused: function () {
                const activeElement = document.activeElement;
                const tagName = activeElement.tagName.toLowerCase();
                return tagName === 'input' || tagName === 'textarea' || 
                       activeElement.contentEditable === 'true';
            },

            /**
             * 提示用户输入CSV文件URL
             */
            promptForCsvUrl: function () {
                const csvUrl = prompt('请输入CSV高亮规则文件的URL:', 
                    this.options.contextPath + '/custom-highlights.csv');
                if (csvUrl) {
                    this.loadHighlightRules(csvUrl);
                }
            },

            /**
             * 从URL加载高亮规则
             */
            loadHighlightRules: function (csvUrl) {
                const self = this;
                
                $.ajax({
                    url: csvUrl,
                    type: 'GET',
                    dataType: 'text',
                    timeout: 5000,
                    success: function (data) {
                        self.parseAndApplyRules(data);
                    },
                    error: function (xhr, status, error) {
                        console.error('Failed to load highlight rules:', error);
                        alert('加载高亮规则失败: ' + error);
                    }
                });
            },

            /**
             * 解析CSV数据并应用高亮规则
             */
            parseAndApplyRules: function (csvData) {
                const self = this;
                const lines = csvData.split('\n');
                const currentPath = this.getCurrentFilePath();
                
                this.highlightRules = [];
                let appliedCount = 0;

                lines.forEach(function (line, index) {
                    line = line.trim();
                    if (!line || line.startsWith('#')) return; // 跳过空行和注释

                    const parts = self.parseCsvLine(line);
                    if (parts.length < 4) {
                        console.warn('Invalid CSV line ' + (index + 1) + ':', line);
                        return;
                    }

                    const rule = {
                        filePath: parts[0],
                        lineNumber: parseInt(parts[1]),
                        startColumn: parseInt(parts[2]),
                        endColumn: parseInt(parts[3]),
                        color: parts[4] || '1',
                        comment: parts[5] || ''
                    };

                    self.highlightRules.push(rule);

                    // 如果规则适用于当前文件，立即应用
                    if (self.isRuleApplicable(rule, currentPath)) {
                        self.applyHighlightRule(rule);
                        appliedCount++;
                    }
                });

                console.log(`Loaded ${this.highlightRules.length} highlight rules, applied ${appliedCount} to current file`);
                if (appliedCount > 0) {
                    this.showNotification(`已应用 ${appliedCount} 个高亮规则`);
                }
            },

            /**
             * 解析CSV行，支持引号包围的字段
             */
            parseCsvLine: function (line) {
                const result = [];
                let current = '';
                let inQuotes = false;
                
                for (let i = 0; i < line.length; i++) {
                    const char = line[i];
                    
                    if (char === '"') {
                        inQuotes = !inQuotes;
                    } else if (char === ',' && !inQuotes) {
                        result.push(current.trim());
                        current = '';
                    } else {
                        current += char;
                    }
                }
                
                result.push(current.trim());
                return result;
            },

            /**
             * 获取当前文件路径
             */
            getCurrentFilePath: function () {
                // 从URL路径中提取文件路径
                const path = window.location.pathname;
                const xrefPrefix = '/xref/';
                const srcPrefix = '/source/xref/';
                
                if (path.includes(xrefPrefix)) {
                    return path.substring(path.indexOf(xrefPrefix) + xrefPrefix.length);
                } else if (path.includes(srcPrefix)) {
                    return path.substring(path.indexOf(srcPrefix) + srcPrefix.length);
                }
                
                return path;
            },

            /**
             * 检查规则是否适用于当前文件
             */
            isRuleApplicable: function (rule, currentPath) {
                if (!currentPath) return false;
                
                // 支持通配符匹配
                const rulePattern = rule.filePath.replace(/\*/g, '.*');
                const regex = new RegExp('^' + rulePattern + '$');
                
                return regex.test(currentPath);
            },

            /**
             * 应用单个高亮规则 - 使用 OpenGrok 的符号系统
             */
            applyHighlightRule: function (rule) {
                // 首先尝试通过行号定位到具体的符号
                const symbolText = this.extractTextFromRule(rule);
                if (!symbolText) {
                    console.warn('Could not extract symbol text for rule:', rule);
                    return;
                }

                // 使用 OpenGrok 的符号高亮系统
                if (this.highlightSymbolInOpenGrok(symbolText, rule.color, rule)) {
                    console.log('Successfully applied highlight using OpenGrok symbol system for:', symbolText);
                } else {
                    console.warn('Could not find matching symbols for:', symbolText, 'Rule:', rule);
                }
            },

            /**
             * 从规则中提取要高亮的文本
             */
            extractTextFromRule: function (rule) {
                const lineContent = this.getLineContent(rule.lineNumber);
                console.log("Line Content:", lineContent);
                if (!lineContent) {
                    return null;
                }

                // 检查范围（CSV中列号从1开始）
                if (rule.startColumn < 1 || rule.startColumn - 1 >= lineContent.length) {
                    console.warn('Start column out of range:', rule.startColumn, 'line length:', lineContent.length);
                    return null;
                }

                // 转换为0基础索引进行字符串操作
                const startIndex = rule.startColumn - 1;
                const endIndex = Math.min(rule.endColumn, lineContent.length + 1) - 1;
                return lineContent.substring(startIndex, endIndex).trim();
            },



            /**
             * 使用 OpenGrok 的符号高亮系统
             */
            highlightSymbolInOpenGrok: function (symbolText, color, rule) {
                if (!symbolText) return false;

                // 查找匹配的符号元素
                const $symbols = this.findSymbolElements(symbolText);
                console.log('Found', $symbols.length, 'symbol elements for text:', symbolText);
                console.log('Symbols:', $symbols);
                
                // if ($symbols.length === 0) {
                //     // 如果没有找到符号，尝试在当前行创建高亮
                //     return this.createLineRangeHighlight(rule, symbolText, color);
                // }

                                // 保存 this 引用以避免作用域问题
                const self = this;
                
                // 应用 OpenGrok 风格的高亮
                $symbols.each(function() {
                    const $el = $(this);
                    
                    console.log('Processing element:', $el[0], 'with text:', $el.text());
                    
                    // 添加 OpenGrok 的高亮类
                    $el.addClass('symbol-highlighted')
                       .addClass('hightlight-color-' + (color || 1));
                    
                    // 添加自定义类和属性
                    $el.addClass(self.options.highlightClass);
                    
                    if (rule.comment) {
                        $el.attr('title', rule.comment)
                           .addClass(self.options.tooltipClass);
                    }
                    
                    // 添加规则信息作为数据属性
                    $el.attr('data-custom-rule', JSON.stringify({
                        line: rule.lineNumber,
                        start: rule.startColumn,
                        end: rule.endColumn,
                        color: rule.color
                    }));
                    
                    console.log('Element after highlight:', $el[0], 'classes:', $el[0].className);
                });

                return true;
            },

            /**
             * 在指定行创建范围高亮（当找不到符号时的替代方案）
             */
            createLineRangeHighlight: function (rule, symbolText, color) {
                const lineInfo = this.getLineInfo(rule.lineNumber);
                if (!lineInfo) {
                    console.warn('Line not found:', rule.lineNumber);
                    return false;
                }

                const lineText = lineInfo.text;
                // 检查范围（CSV中列号从1开始）
                if (rule.startColumn < 1 || rule.startColumn - 1 >= lineText.length) {
                    console.warn('Start column out of range:', rule.startColumn, 'line length:', lineText.length);
                    return false;
                }

                const actualEndColumn = Math.min(rule.endColumn, lineText.length + 1);
                
                // 创建高亮元素
                const $highlight = $('<span>')
                    .addClass('symbol-highlighted') // 使用 OpenGrok 的类
                    .addClass('hightlight-color-' + (color || 1))
                    .addClass(this.options.highlightClass)
                    .attr('data-line', rule.lineNumber)
                    .attr('data-start', rule.startColumn)
                    .attr('data-end', actualEndColumn);

                if (rule.comment) {
                    $highlight.attr('title', rule.comment)
                             .addClass(this.options.tooltipClass);
                }

                // 包装指定范围的文本
                this.wrapTextInLineRange(lineInfo, rule.startColumn, actualEndColumn, $highlight);
                console.log('Created line range highlight for:', symbolText, 'at line', rule.lineNumber);
                return true;
            },

            /**
             * 查找匹配的符号元素
             */
            findSymbolElements: function (symbolText) {
                console.log('Searching for symbol text:', symbolText);
                
                // 定义所有 OpenGrok 符号选择器（按优先级排序）
                const symbolSelectors = [
                    // 优先级1: 主要的 intelliWindow 符号
                    'a.intelliWindow-symbol',

                    // 优先级2: 组合选择器 - 最精确的匹配
                    'a.xm.intelliWindow-symbol',   // macro + intelliWindow
                    'a.xv.intelliWindow-symbol',   // variable + intelliWindow
                    'a.d.intelliWindow-symbol',    // definition + intelliWindow
                    'a.xf.intelliWindow-symbol',   // function + intelliWindow
                    'a.xa.intelliWindow-symbol',   // argument + intelliWindow
                    'a.xl.intelliWindow-symbol',   // local + intelliWindow
                    'a.xs.intelliWindow-symbol',   // struct + intelliWindow
                    'a.xmb.intelliWindow-symbol',  // member + intelliWindow
                    'a.xfld.intelliWindow-symbol', // field + intelliWindow
                    'a.xmt.intelliWindow-symbol',  // method + intelliWindow
                    'a.xsr.intelliWindow-symbol',  // subroutine + intelliWindow
                    'a.xlbl.intelliWindow-symbol', // label + intelliWindow
                    
                    // 优先级3: OpenGrok 的各种符号类型
                    'a.d',    // symbol definition
                    'a.xf',   // function
                    'a.xm',   // macro
                    'a.xv',   // variable
                    'a.xa',   // argument
                    'a.xl',   // local
                    'a.xs',   // struct
                    'a.xmb',  // member
                    'a.xfld', // field
                    'a.xmt',  // method
                    'a.xsr',  // subroutine
                    'a.xlbl'  // label
                ];
                
                // 尝试所有选择器，找到最佳匹配
                for (const selector of symbolSelectors) {
                    const $symbols = $(selector);
                    if ($symbols.length > 0) {
                        console.log('Found', $symbols.length, 'elements with selector:', selector);
                        
                        const $matches = this.filterSymbolsByText($symbols, symbolText);
                        if ($matches.length > 0) {
                            console.log('Found', $matches.length, 'text matches with selector:', selector);
                            return $matches;
                        }
                    }
                }
                
                // // 如果上面都没找到，尝试更通用的选择器
                // const fallbackSelectors = [
                //     '#src a[href]',     // 源码区域的所有链接
                //     '#src0 a[href]',    // 备用源码区域的链接
                //     'pre a[href]'       // pre 块中的链接
                // ];
                
                // for (const selector of fallbackSelectors) {
                //     const $symbols = $(selector);
                //     if ($symbols.length > 0) {
                //         console.log('Fallback: Found', $symbols.length, 'elements with selector:', selector);
                        
                //         const $matches = this.filterSymbolsByText($symbols, symbolText);
                //         if ($matches.length > 0) {
                //             console.log('Fallback: Found', $matches.length, 'text matches with selector:', selector);
                //             return $matches;
                //         }
                //     }
                // }

                console.warn('No matching symbol elements found for:', symbolText);
                return $();
            },

            /**
             * 根据文本过滤符号元素
             */
            filterSymbolsByText: function ($symbols, symbolText) {
                console.log('Filtering', $symbols.length, 'symbols for text "' + symbolText + '"');
                
                // 过滤出文本匹配的元素
                const $exactMatches = $symbols.filter(function () {
                    const elementText = $(this).text().trim();
                    return elementText === symbolText;
                });

                if ($exactMatches.length > 0) {
                    console.log('Found', $exactMatches.length, 'exact matches for "' + symbolText + '"');
                    this.logMatchedElements($exactMatches, 'exact');
                    return $exactMatches;
                }

                // // 如果没有找到精确匹配，尝试查找包含该文本的元素
                // const $partialMatches = $symbols.filter(function () {
                //     const elementText = $(this).text().trim();
                //     return elementText.includes(symbolText) && symbolText.length > 1; // 避免匹配单个字符
                // });

                // if ($partialMatches.length > 0) {
                //     console.log('Found', $partialMatches.length, 'partial matches for "' + symbolText + '"');
                //     this.logMatchedElements($partialMatches, 'partial');
                //     return $partialMatches;
                // }

                console.log('No matches found for "' + symbolText + '"');
                return $();
            },

            /**
             * 记录匹配到的元素信息（用于调试）
             */
            logMatchedElements: function ($elements, matchType) {
                if ($elements.length === 0) return;

                console.log('=== ' + matchType.toUpperCase() + ' MATCHES ===');
                $elements.each(function(index, element) {
                    const $el = $(element);
                    const classes = element.className || 'no-classes';
                    const text = $el.text().trim();
                    const href = $el.attr('href') || 'no-href';
                    
                    console.log(index + ':', {
                        text: text,
                        classes: classes,
                        href: href,
                        tagName: element.tagName.toLowerCase()
                    });
                });
                console.log('=== END MATCHES ===');
            },


            /**
             * 获取指定行号的行信息
             */
            getLineInfo: function (lineNumber) {
                const $anchor = this.getLineAnchor(lineNumber);
                if (!$anchor.length) {
                    return null;
                }

                // 获取行内容 - 从锚点开始到下一行锚点或行结束
                const lineText = this.extractLineText($anchor);
                
                return {
                    $anchor: $anchor,
                    $container: $anchor.parent(),
                    text: lineText,
                    lineNumber: lineNumber
                };
            },

            /**
             * 获取指定行号的行内容文本
             */
            getLineContent: function (lineNumber) {
                const $anchor = this.getLineAnchor(lineNumber);
                if (!$anchor.length) {
                    return null;
                }

                return this.extractLineText($anchor);
            },

            /**
             * 获取指定行号的锚点元素
             */
            getLineAnchor: function (lineNumber) {
                // 尝试多种选择器，兼容不同版本的OpenGrok
                const selectors = [
                    `div#src a[name="${lineNumber}"]`,
                    `div#src0 a[name="${lineNumber}"]`,
                    `#src a[name="${lineNumber}"]`,
                    `a[name="${lineNumber}"]`
                ];

                for (const selector of selectors) {
                    const $anchor = $(selector);
                    if ($anchor.length) {
                        return $anchor;
                    }
                }

                return $();
            },

            /**
             * 从锚点提取行文本内容
             */
            extractLineText: function ($anchor) {
                if (!$anchor.length) {
                    return '';
                }

                let text = '';
                let $current = $anchor;
                
                // 从锚点开始，遍历后续的兄弟节点直到下一个行锚点
                while ($current.length) {
                    $current = $current.next();
                    
                    if (!$current.length) break;
                    
                    // 如果遇到下一个行锚点，停止
                    if ($current.is('a[name]') && /^\d+$/.test($current.attr('name'))) {
                        break;
                    }
                    
                    // 收集文本内容
                    if ($current[0].nodeType === Node.TEXT_NODE) {
                        text += $current[0].textContent;
                    } else if ($current[0].nodeType === Node.ELEMENT_NODE) {
                        text += $current.text();
                    }
                    
                    // 如果遇到换行符，停止
                    if (text.includes('\n')) {
                        text = text.split('\n')[0];
                        break;
                    }
                }

                // 如果上面的方法没有找到内容，尝试解析整个容器
                if (!text.trim()) {
                    text = this.extractLineTextFromContainer($anchor);
                }

                return text;
            },

            /**
             * 从容器中提取指定行的文本（备用方法）
             */
            extractLineTextFromContainer: function ($anchor) {
                const lineNumber = parseInt($anchor.attr('name'));
                const $container = $anchor.parent();
                const allText = $container.text();
                const lines = allText.split('\n');
                
                // 尝试找到对应行号的文本
                if (lines.length >= lineNumber && lineNumber > 0) {
                    return lines[lineNumber - 1] || '';
                }

                return '';
            },

            /**
             * 在行范围内包装文本
             */
            wrapTextInLineRange: function (lineInfo, startCol, endCol, $wrapper) {
                const $container = lineInfo.$container;
                const targetText = lineInfo.text.substring(startCol - 1, endCol - 1);
                
                if (!targetText.trim()) {
                    console.warn('No text to highlight in range');
                    return;
                }

                // 查找包含目标文本的元素
                const $textElements = $container.find('*').addBack().filter(function() {
                    return $(this).text().includes(targetText);
                });

                if ($textElements.length === 0) {
                    console.warn('Could not find text elements containing:', targetText);
                    return;
                }

                // 在最精确的元素中进行文本替换
                const $target = $textElements.last();
                const originalText = $target.text();
                const startIndex = originalText.indexOf(targetText);
                
                if (startIndex === -1) {
                    console.warn('Target text not found in element');
                    return;
                }

                // 分割文本并插入高亮元素
                const beforeText = originalText.substring(0, startIndex);
                const afterText = originalText.substring(startIndex + targetText.length);
                
                $wrapper.text(targetText);
                
                // 替换元素内容
                $target.empty()
                      .append(document.createTextNode(beforeText))
                      .append($wrapper)
                      .append(document.createTextNode(afterText));
            },

            /**
             * 获取元素内的所有文本节点
             */
            getTextNodes: function (element) {
                const textNodes = [];
                const walker = document.createTreeWalker(
                    element,
                    NodeFilter.SHOW_TEXT,
                    null,
                    false
                );

                let node;
                while ((node = walker.nextNode())) {
                    textNodes.push(node);
                }

                return textNodes;
            },

            /**
             * 清除所有自定义高亮 - 复用 OpenGrok 的高亮系统
             */
            clearAllHighlights: function () {
                // 清除使用 OpenGrok 符号高亮系统的元素
                $('.' + this.options.highlightClass).each(function () {
                    const $el = $(this);
                    
                    // 移除 OpenGrok 的高亮类
                    $el.removeClass('symbol-highlighted')
                       .removeClass(function (index, className) {
                           return (className.match(/(^|\s)hightlight-color-\S+/g) || []).join(' ');
                       });
                    
                    // 移除自定义类和属性
                    $el.removeClass(this.options.highlightClass)
                       .removeClass(this.options.tooltipClass)
                       .removeAttr('title')
                       .removeAttr('data-custom-rule');
                }.bind(this));
                
                // 清除自定义包装的高亮元素
                $('.' + this.options.highlightClass).each(function () {
                    const $this = $(this);
                    // 如果是包装元素，用文本内容替换
                    if ($this.parent().length && !$this.hasClass('symbol-highlighted')) {
                        $this.replaceWith($this.text());
                    }
                });
                
                this.highlightRules = [];
                this.showNotification('已清除所有自定义高亮');
            },

            /**
             * 显示通知消息
             */
            showNotification: function (message) {
                // 创建简单的通知元素
                const $notification = $('<div>')
                    .addClass('custom-highlight-notification')
                    .text(message)
                    .css({
                        position: 'fixed',
                        top: '20px',
                        right: '20px',
                        background: '#4CAF50',
                        color: 'white',
                        padding: '10px 20px',
                        borderRadius: '4px',
                        zIndex: 9999,
                        fontSize: '14px'
                    });

                $('body').append($notification);

                // 3秒后自动消失
                setTimeout(function () {
                    $notification.fadeOut(300, function () {
                        $notification.remove();
                    });
                }, 3000);
            },

            /**
             * 从JSON对象直接加载规则（用于程序化调用）
             */
            loadRulesFromObject: function (rules) {
                const currentPath = this.getCurrentFilePath();
                let appliedCount = 0;

                this.highlightRules = rules;

                rules.forEach(function (rule) {
                    if (this.isRuleApplicable(rule, currentPath)) {
                        this.applyHighlightRule(rule);
                        appliedCount++;
                    }
                }.bind(this));

                console.log(`Applied ${appliedCount} highlight rules from object`);
                return appliedCount;
            }
        };
    };

    // 注册到全局对象
    $.customHighlight = new customHighlight();

    // 页面加载完成后自动初始化
    $(document).ready(function () {
        // 只在代码查看页面初始化
        if ($('#src, #src0, div[id^="src"]').length > 0) {
            // 等待 intelliWindow 初始化完成后再初始化 customHighlight
            setTimeout(function() {
                $.customHighlight.init();
            }, 100);
        }
    });

})(window, document, jQuery); 