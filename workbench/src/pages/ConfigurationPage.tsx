import { useState } from 'react';
import { AgentConfigPage } from './AgentConfigPage';
import { ModelConfigPage } from './ModelConfigPage';

export function ConfigurationPage() {
  const [tab, setTab] = useState<'models' | 'agents'>('models');

  return <>
    <nav className="page-tabs" aria-label="Agent 与模型配置">
      <button type="button" className={tab === 'models' ? 'active' : ''} onClick={() => setTab('models')}>模型配置</button>
      <button type="button" className={tab === 'agents' ? 'active' : ''} onClick={() => setTab('agents')}>Agent 配置</button>
    </nav>
    {/* 两个视图复用同一个 agent-config 查询和 CRUD，不再维护业务模型池。 */}
    {tab === 'models' ? <ModelConfigPage /> : <AgentConfigPage />}
  </>;
}
