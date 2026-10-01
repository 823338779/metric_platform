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
        colorPrimary: '#315bc3',
        borderRadius: 8,
        colorText: '#233247',
        colorTextSecondary: '#5b6b82',
        colorTextPlaceholder: '#66758b',
        colorBorderSecondary: '#e7ecf3',
        colorBgLayout: '#f3f5f8',
        fontFamily: "Inter, 'Segoe UI', 'Microsoft YaHei', sans-serif",
        controlHeight: 34,
        fontSize: 15,
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
