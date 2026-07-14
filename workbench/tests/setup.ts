import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// jsdom 尚未实现原生 dialog 行为，测试中补齐浏览器已有的两个方法。
HTMLDialogElement.prototype.showModal ??= function showModal() {
  this.setAttribute('open', '');
};
HTMLDialogElement.prototype.close ??= function close() {
  this.removeAttribute('open');
  this.dispatchEvent(new Event('close'));
};

afterEach(cleanup);
