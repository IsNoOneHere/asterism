import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

type MarkdownContentProps = {
  markdown: string;
  className?: string;
};

export function MarkdownContent({ markdown, className = '' }: MarkdownContentProps) {
  return (
    <div className={`markdown-content ${className}`.trim()}>
      {/* 计划内容来自模型，关闭原始 HTML，避免内容注入页面。 */}
      <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{markdown}</ReactMarkdown>
    </div>
  );
}
