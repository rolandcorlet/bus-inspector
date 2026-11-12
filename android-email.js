/**
 * Bus Inspector v3.0 — Android Native APK & Web Bridge (android-email.js)
 *
 * Features:
 *   1. Zero-Interruption Direct Camera Capture (window.BusPhotoCapture):
 *      - On Android APK: Immediately launches the native Android Camera app
 *        (MediaStore.ACTION_IMAGE_CAPTURE) with zero intermediate modal or chooser menu,
 *        retrieves the EXIF-oriented, scaled JPEG via consumeLastCapturedPhoto(),
 *        and adds it straight into the app.
 *      - On Mobile/Desktop Web: Immediately triggers native camera input
 *        (<input type="file" accept="image/*" capture="environment">) and auto-compresses.
 *   2. Guaranteed Local Downloads Saving (window.BusEmailSystem.saveFileToDownloads):
 *      - Saves HTML, JSON, CSV, and JPG photo files directly into the device's local
 *        Downloads folder (/storage/emulated/0/Download/<filename>) via MediaStore.Downloads
 *        (Android 10+) and Environment.DIRECTORY_DOWNLOADS (Android 9 and below).
 *   3. Improved APK Emailing with Multi-Attachment Support (window.sendBusEmail):
 *      - Saves the HTML report (and Bus Condition photos) to local Downloads first.
 *      - Launches the Android Email app (Gmail, Outlook, etc.) addressed to
 *        transportbusreports@gmail.com with Subject, Body, the HTML report file,
 *        AND individual JPEG photos automatically attached via ReportFileProvider.
 */
(function () {
  'use strict';

  var DEFAULT_TARGET_EMAIL = 'transportbusreports@gmail.com';
  var OUTBOX_STORAGE_KEY = 'bus_reports_outbox_log';

  function sanitizeEmail(email) {
    var raw = String(email || DEFAULT_TARGET_EMAIL).trim().replace(/\s+/g, '');
    return raw || DEFAULT_TARGET_EMAIL;
  }

  function isAndroidApk() {
    return Boolean(
      (window.AndroidBridge && typeof window.AndroidBridge === 'object') ||
        window.location.protocol === 'file:'
    );
  }

  // ============================================================================
  // 1. PHOTO COMPRESSION & NORMALIZATION ENGINE
  // ============================================================================

  function compressBusPhoto(fileOrDataUrl, options) {
    options = options || {};
    var maxDim = options.maxDimension || 1440;
    var quality = options.quality || 0.85;

    return new Promise(function (resolve, reject) {
      var processDataUrl = function (rawDataUrl) {
        if (!rawDataUrl || typeof rawDataUrl !== 'string') {
          reject(new Error('Invalid image data'));
          return;
        }
        var img = new Image();
        img.onload = function () {
          try {
            var w = img.naturalWidth || img.width || 800;
            var h = img.naturalHeight || img.height || 600;
            if (w > maxDim || h > maxDim) {
              if (w >= h) {
                h = Math.round((h * maxDim) / w);
                w = maxDim;
              } else {
                w = Math.round((w * maxDim) / h);
                h = maxDim;
              }
            }
            var canvas = document.createElement('canvas');
            canvas.width = w;
            canvas.height = h;
            var ctx = canvas.getContext('2d');
            if (!ctx) {
              resolve({
                dataUrl: rawDataUrl,
                size: Math.round((rawDataUrl.length * 3) / 4),
                type: 'image/jpeg'
              });
              return;
            }
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, w, h);
            ctx.drawImage(img, 0, 0, w, h);
            var compressedUrl = canvas.toDataURL('image/jpeg', quality);
            var byteEstimate = Math.round((compressedUrl.length * 3) / 4);
            resolve({
              dataUrl: compressedUrl,
              size: byteEstimate,
              type: 'image/jpeg',
              width: w,
              height: h
            });
          } catch (err) {
            resolve({
              dataUrl: rawDataUrl,
              size: Math.round((rawDataUrl.length * 3) / 4),
              type: 'image/jpeg'
            });
          }
        };
        img.onerror = function () {
          resolve({
            dataUrl: rawDataUrl,
            size: Math.round((rawDataUrl.length * 3) / 4),
            type: 'image/jpeg'
          });
        };
        img.src = rawDataUrl;
      };

      if (typeof fileOrDataUrl === 'string') {
        processDataUrl(fileOrDataUrl);
        return;
      }

      if (fileOrDataUrl && typeof FileReader !== 'undefined') {
        var reader = new FileReader();
        reader.onload = function () {
          processDataUrl(String(reader.result || ''));
        };
        reader.onerror = function (err) {
          reject(err);
        };
        reader.readAsDataURL(fileOrDataUrl);
      } else {
        reject(new Error('Unsupported photo input'));
      }
    });
  }

  // ============================================================================
  // 2. ZERO-INTERRUPTION DIRECT CAMERA CAPTURE
  // ============================================================================

  /**
   * Immediately opens the device camera with NO intermediate modal or interruption,
   * and returns the captured photo(s) to opts.onCapture([{ name, size, type, data, capturedAt }]).
   */
  function takePhotoDirect(opts) {
    opts = opts || {};
    var onCapture = typeof opts.onCapture === 'function' ? opts.onCapture : function () {};

    var handleNativeDataUrl = function (dataUrl) {
      window.__onAndroidCameraReady = null;
      window.__onAndroidCameraResult = null;
      if (!dataUrl || typeof dataUrl !== 'string') return;
      var now = new Date();
      var approxBytes = Math.round((dataUrl.length * 3) / 4);
      onCapture([
        {
          name: 'photo_' + now.getTime() + '.jpg',
          size: approxBytes,
          type: 'image/jpeg',
          data: dataUrl,
          capturedAt: now.toISOString(),
          capturedLocal: now.toLocaleString('en-GB', { hour12: true })
        }
      ]);
    };

    // 1. Native Android APK Direct Camera Launch (MediaStore.ACTION_IMAGE_CAPTURE)
    if (isAndroidApk()) {
      window.__onAndroidCameraReady = function () {
        var dataUrl = '';
        try {
          if (
            window.AndroidBridge &&
            typeof window.AndroidBridge.consumeLastCapturedPhoto === 'function'
          ) {
            dataUrl = window.AndroidBridge.consumeLastCapturedPhoto();
          }
        } catch (err) {
          console.warn('consumeLastCapturedPhoto error:', err);
        }
        handleNativeDataUrl(dataUrl);
      };

      window.__onAndroidCameraResult = function (dataUrl) {
        handleNativeDataUrl(dataUrl);
      };

      try {
        if (window.AndroidBridge && typeof window.AndroidBridge.takePhoto === 'function') {
          var launched = window.AndroidBridge.takePhoto();
          if (launched !== false && launched !== 'ERROR') {
            return;
          }
        }
      } catch (e) {
        console.warn('AndroidBridge.takePhoto error:', e);
      }

      try {
        var promptAck = window.prompt('__BUS_CAMERA__', opts.title || 'Take Photo');
        if (promptAck === 'OK') {
          return;
        }
      } catch (e2) {}
    }

    // 2. If a page inputElement was provided, trigger it directly
    if (opts.inputElement && typeof opts.inputElement.click === 'function') {
      opts.inputElement.click();
      return;
    }

    // 3. Direct hidden <input type="file" accept="image/*" capture="environment">
    var tempInput = document.getElementById('bus-direct-camera-input');
    if (tempInput) {
      tempInput.remove();
    }
    tempInput = document.createElement('input');
    tempInput.type = 'file';
    tempInput.id = 'bus-direct-camera-input';
    tempInput.accept = 'image/*';
    tempInput.setAttribute('capture', 'environment');
    tempInput.style.display = 'none';
    document.body.appendChild(tempInput);

    tempInput.addEventListener('change', function (ev) {
      var files = Array.from(ev.target.files || []).filter(function (f) {
        return f && (!f.type || f.type.indexOf('image/') === 0);
      });
      if (!files.length) {
        tempInput.remove();
        return;
      }
      Promise.all(
        files.map(function (file, idx) {
          return compressBusPhoto(file).then(function (res) {
            var now = new Date();
            return {
              name: file.name
                ? file.name.replace(/\.[^.]+$/, '.jpg')
                : 'photo_' + now.getTime() + '_' + (idx + 1) + '.jpg',
              size: res.size,
              type: 'image/jpeg',
              data: res.dataUrl,
              capturedAt: now.toISOString(),
              capturedLocal: now.toLocaleString('en-GB', { hour12: true })
            };
          });
        })
      )
        .then(function (results) {
          tempInput.remove();
          onCapture(results);
        })
        .catch(function (err) {
          console.error('Failed to process photo:', err);
          tempInput.remove();
        });
    });

    tempInput.click();
  }

  // ============================================================================
  // 3. GUARANTEED LOCAL DOWNLOADS SAVING (HTML, JSON, CSV, PHOTOS)
  // ============================================================================

  function sanitizeFilename(filename, defaultName) {
    var raw = String(filename || defaultName || 'bus_report.html').trim();
    return raw.replace(/[^a-zA-Z0-9._-]/g, '_');
  }

  /**
   * Saves text or base64 content directly to the device's local Downloads folder.
   */
  function saveFileToDownloads(content, filename, mimeType, isBase64) {
    if (content === null || content === undefined) return false;
    var safeFilename = sanitizeFilename(filename, 'bus_report.html');
    var mime = mimeType || 'text/html';
    var base64Flag = Boolean(isBase64);

    // 1. Native Android APK Bridge (writes to MediaStore.Downloads / Environment.DIRECTORY_DOWNLOADS)
    if (window.AndroidBridge && typeof window.AndroidBridge.saveFileToDownloads === 'function') {
      try {
        var result = window.AndroidBridge.saveFileToDownloads(
          String(content),
          safeFilename,
          mime,
          base64Flag
        );
        if (result && String(result).indexOf('ERROR') !== 0) {
          return true;
        }
      } catch (e) {
        console.warn('AndroidBridge.saveFileToDownloads error:', e);
      }
    }

    if (
      !base64Flag &&
      window.AndroidBridge &&
      typeof window.AndroidBridge.saveHtmlToDownloads === 'function'
    ) {
      try {
        if (window.AndroidBridge.saveHtmlToDownloads(String(content), safeFilename)) {
          return true;
        }
      } catch (e2) {}
    }

    // 2. Android WebChromeClient onJsPrompt fallback
    if (window.location.protocol === 'file:') {
      try {
        var promptKey = base64Flag
          ? '__BUS_SAVE_BASE64__:' + safeFilename + ':' + mime
          : '__BUS_SAVE_HTML__:' + safeFilename;
        var res = window.prompt(promptKey, String(content));
        if (res === 'OK' || res === 'SAVED') {
          return true;
        }
      } catch (e3) {
        console.warn('onJsPrompt save fallback error:', e3);
      }
    }

    // 3. Browser Blob / Data URL Download
    try {
      var a = document.createElement('a');
      if (base64Flag) {
        var dataUrl =
          String(content).indexOf('data:') === 0
            ? String(content)
            : 'data:' + mime + ';base64,' + String(content);
        a.href = dataUrl;
        a.download = safeFilename;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        return true;
      }

      var blob = new Blob([content], { type: mime + ';charset=utf-8' });
      var url = URL.createObjectURL(blob);
      a.href = url;
      a.download = safeFilename;
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);

      setTimeout(function () {
        try {
          URL.revokeObjectURL(url);
        } catch (e4) {}
      }, 60000);
      return true;
    } catch (err) {
      console.error('Browser file download failed:', err);
      return false;
    }
  }

  function saveDataUrlToDownloads(dataUrl, filename) {
    if (!dataUrl) return false;
    var safeFilename = sanitizeFilename(filename, 'photo_' + Date.now() + '.jpg');
    var mime = 'image/jpeg';
    var match = String(dataUrl).match(/^data:([^;]+);base64,/);
    if (match && match[1]) {
      mime = match[1];
    }
    return saveFileToDownloads(dataUrl, safeFilename, mime, true);
  }

  function saveHtmlFileBeforeEmail(htmlContent, filename) {
    return saveFileToDownloads(htmlContent, filename, 'text/html', false);
  }

  // ============================================================================
  // 4. IMPROVED APK EMAILING FUNCTION WITH ATTACHMENT & LOCAL OUTBOX
  // ============================================================================

  function saveToLocalOutbox(entry) {
    var record = {
      id: 'rep_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      to: sanitizeEmail(entry.to),
      subject: entry.subject || 'Bus Report',
      body: entry.body || '',
      filename: entry.filename || 'report.html',
      sizeBytes: entry.htmlContent ? entry.htmlContent.length : 0,
      timestamp: new Date().toISOString(),
      localTime: new Date().toLocaleString('en-GB', { hour12: true }),
      status: entry.status || 'saved_and_emailed',
      method: entry.method || (isAndroidApk() ? 'android-intent-attachment' : 'web-mailto'),
      htmlContent: entry.htmlContent || ''
    };
    try {
      var raw = localStorage.getItem(OUTBOX_STORAGE_KEY);
      var list = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(list)) list = [];
      list = list.filter(function (item) {
        return item && item.filename !== record.filename;
      });
      list.unshift(record);
      if (list.length > 10) list = list.slice(0, 10);
      localStorage.setItem(OUTBOX_STORAGE_KEY, JSON.stringify(list));
    } catch (e) {
      try {
        var lightRecord = Object.assign({}, record, { htmlContent: '' });
        var raw2 = localStorage.getItem(OUTBOX_STORAGE_KEY);
        var list2 = raw2 ? JSON.parse(raw2) : [];
        if (!Array.isArray(list2)) list2 = [];
        list2.unshift(lightRecord);
        if (list2.length > 10) list2 = list2.slice(0, 10);
        localStorage.setItem(OUTBOX_STORAGE_KEY, JSON.stringify(list2));
      } catch (e2) {}
    }
    return record;
  }

  function buildMailtoUrl(to, subject, body, filename) {
    var recipient = sanitizeEmail(to);
    var cleanBody = String(body || '');
    if (filename && cleanBody.indexOf(filename) === -1) {
      cleanBody +=
        '\r\n\r\n--------------------------------------------------\r\n' +
        'Report File Saved to Device Downloads: ' +
        filename +
        '\r\n(If not automatically attached by your email app, attach "' +
        filename +
        '" from your Downloads folder.)';
    }
    cleanBody = cleanBody.replace(/\r?\n/g, '\r\n');
    return (
      'mailto:' +
      recipient +
      '?subject=' +
      encodeURIComponent(subject || 'Bus Inspector Report') +
      '&body=' +
      encodeURIComponent(cleanBody)
    );
  }

  function canUseWebShareWithFile(htmlContent, filename) {
    try {
      if (
        typeof navigator === 'undefined' ||
        typeof navigator.share !== 'function' ||
        typeof navigator.canShare !== 'function' ||
        typeof File === 'undefined'
      ) {
        return false;
      }
      var file = new File([htmlContent || ''], filename || 'report.html', { type: 'text/html' });
      return navigator.canShare({ files: [file] });
    } catch (e) {
      return false;
    }
  }

  window.sendBusEmail = async function (options) {
    if (!options || typeof options !== 'object') return false;

    var to = sanitizeEmail(options.to || DEFAULT_TARGET_EMAIL);
    var subject = options.subject || 'Bus Inspector Report';
    var filename = sanitizeFilename(
      options.filename,
      'bus_report_' + Date.now() + '.html'
    );
    var htmlContent = options.htmlContent || '';
    var body = String(options.body || '');
    var savePhotosToDownloads =
      options.savePhotosToDownloads !== undefined
        ? Boolean(options.savePhotosToDownloads)
        : filename.toLowerCase().indexOf('condition') !== -1;

    // 1. Log to local outbox history
    saveToLocalOutbox({
      to: to,
      subject: subject,
      body: body,
      filename: filename,
      htmlContent: htmlContent
    });

    // 2. Native Android APK: Save to Downloads AND launch Email app with HTML + JPG photos attached!
    if (window.AndroidBridge) {
      try {
        if (typeof window.AndroidBridge.sendEmailWithPhotos === 'function') {
          var resPhotos = window.AndroidBridge.sendEmailWithPhotos(
            to,
            subject,
            body,
            htmlContent,
            filename,
            savePhotosToDownloads
          );
          if (resPhotos && String(resPhotos).indexOf('ERROR') !== 0) {
            return true;
          }
        }
        if (typeof window.AndroidBridge.sendEmail === 'function') {
          var res = window.AndroidBridge.sendEmail(to, subject, body, htmlContent, filename);
          if (res && String(res).indexOf('ERROR') !== 0) {
            return true;
          }
        }
      } catch (err) {
        console.warn('AndroidBridge.sendEmail error:', err);
      }
    }

    // 3. Fallback: Save HTML file to local Downloads first
    if (htmlContent) {
      saveFileToDownloads(htmlContent, filename, 'text/html', false);
    }

    // 4. Browser / Web fallback via mailto
    var mailtoUrl = buildMailtoUrl(to, subject, body, filename);
    try {
      var mailLink = document.createElement('a');
      mailLink.href = mailtoUrl;
      mailLink.style.display = 'none';
      document.body.appendChild(mailLink);
      mailLink.click();
      document.body.removeChild(mailLink);
    } catch (e3) {
      window.location.href = mailtoUrl;
    }

    return true;
  };

  window.compressBusPhoto = compressBusPhoto;

  window.BusPhotoCapture = {
    takePhoto: takePhotoDirect,
    openModal: takePhotoDirect,
    compressPhoto: compressBusPhoto
  };

  window.BusEmailSystem = {
    version: '3.0.0',
    targetEmail: DEFAULT_TARGET_EMAIL,
    sanitizeEmail: sanitizeEmail,
    isAndroidApk: isAndroidApk,
    saveFileToDownloads: saveFileToDownloads,
    saveDataUrlToDownloads: saveDataUrlToDownloads,
    saveHtmlFileBeforeEmail: saveHtmlFileBeforeEmail,
    buildMailtoUrl: buildMailtoUrl,
    canUseWebShareWithFile: canUseWebShareWithFile,
    getOutbox: function () {
      try {
        var raw = localStorage.getItem(OUTBOX_STORAGE_KEY);
        return raw ? JSON.parse(raw) : [];
      } catch (e) {
        return [];
      }
    },
    clearOutbox: function () {
      try {
        localStorage.removeItem(OUTBOX_STORAGE_KEY);
      } catch (e) {}
    }
  };
})();
