import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { createRoot } from 'react-dom/client';
import App from './App';
import './global.css';

createRoot(document.getElementById('root')!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: '#3867d6',
        borderRadius: 8,
        colorText: '#25334a',
        colorTextSecondary: '#69778b',
        colorBorderSecondary: '#e7ecf3',
        colorBgLayout: '#f5f7fb',
        fontFamily: "Inter, 'Segoe UI', 'Microsoft YaHei', sans-serif",
        controlHeight: 34,
      },
      components: {
        Menu: { itemSelectedBg: '#eaf0ff', itemSelectedColor: '#315fd3', itemBorderRadius: 6 },
        Card: { headerFontSize: 15, headerHeight: 54 },
        Tabs: { titleFontSize: 13, horizontalItemGutter: 24 },
      },
    }}
  >
    <AntApp>
      <App />
    </AntApp>
  </ConfigProvider>,
);
