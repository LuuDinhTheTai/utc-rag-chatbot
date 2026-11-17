import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';
import { installMock } from './mock/browser';
import MockControls from './mock/Controls';

installMock();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
    <MockControls />
  </StrictMode>,
);
