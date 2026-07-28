import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { MarkdownContent } from './MarkdownContent';

test('renders common Markdown and GFM elements as readable content', () => {
  render(<MarkdownContent markdown={[
    '# 心跳接口实施计划',
    '',
    '**请求方法**：`GET`',
    '',
    '- 返回 200',
    '- 响应 `success`',
    '',
    '| 检查项 | 预期 |',
    '| --- | --- |',
    '| HTTP 状态 | 200 |',
  ].join('\n')} />);

  expect(screen.getByRole('heading', { level: 1, name: '心跳接口实施计划' })).toBeInTheDocument();
  expect(screen.getByText('请求方法')).toHaveProperty('tagName', 'STRONG');
  expect(screen.getByRole('list')).toBeInTheDocument();
  expect(screen.getByText('GET')).toHaveProperty('tagName', 'CODE');
  expect(screen.getByRole('table')).toBeInTheDocument();
  expect(screen.queryByText(/^# 心跳接口实施计划$/)).not.toBeInTheDocument();
});

test('does not inject raw HTML from Markdown', () => {
  const { container } = render(<MarkdownContent markdown={'正文\n\n<script>alert("xss")</script>\n\n<img src=x onerror=alert(1)>'} />);

  expect(screen.getByText('正文')).toBeInTheDocument();
  expect(container.querySelector('script')).toBeNull();
  expect(container.querySelector('img')).toBeNull();
});
