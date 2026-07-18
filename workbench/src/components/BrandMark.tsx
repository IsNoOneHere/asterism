type Props = {
  inverse?: boolean;
  compact?: boolean;
};

export function BrandMark({ inverse = false, compact = false }: Props) {
  return (
    <div className={`brand-mark${inverse ? ' inverse' : ''}${compact ? ' compact' : ''}`}>
      {/* 紧凑模式裁掉外框并放大星轨，完整字标仅用于登录页。 */}
      <svg viewBox={compact ? '8 8 28 28' : '0 0 44 44'} aria-hidden="true">
        {!compact && <rect x="1" y="1" width="42" height="42" rx="11" />}
        <path d="M12 28.5 20 20l7 4.5 5-9" />
        <circle cx="12" cy="28.5" r="2.2" />
        <circle cx="20" cy="20" r="2.2" />
        <circle cx="27" cy="24.5" r="2.2" />
        <circle cx="32" cy="15.5" r="2.2" />
      </svg>
      <span className="brand-copy">
        <strong>Asterism</strong>
        {!compact && <small>Workbench</small>}
      </span>
    </div>
  );
}
