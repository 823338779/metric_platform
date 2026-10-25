import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { createRoot } from 'react-dom/client';
import App from './App';
import './global.css';
import './workbench.css';

createRoot(document.getElementById('root')!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: '#2859c5',
        borderRadius: 6,
        colorText: '#202d40',
        colorTextSecondary: '#58677b',
        colorTextPlaceholder: '#66758b',
        colorBorderSecondary: '#e7ecf3',
        colorBgLayout: '#f5f6f8',
        fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
        controlHeight: 36,
        fontSize: 14,
      },
      components: {
        Menu: { itemSelectedBg: '#eaf0ff', itemSelectedColor: '#315fd3', itemBorderRadius: 6 },
        Card: { headerFontSize: 15, headerHeight: 60, borderRadiusLG: 12 },
        Tabs: { titleFontSize: 13, horizontalItemGutter: 24 },
      },
    }}
  >
    <AntApp>
      <App />
    </AntApp>
  </ConfigProvider>,
);
