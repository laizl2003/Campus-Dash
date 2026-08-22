import { useEffect, useState } from 'react';

/** 极简 hash 路由：#/square、#/detail/123、#/publish、#/mine、#/wallet */
export function useHashRoute(): string {
  const [route, setRoute] = useState(location.hash.slice(1) || '/square');
  useEffect(() => {
    const onChange = () => setRoute(location.hash.slice(1) || '/square');
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

export function navigate(path: string) {
  location.hash = '#' + path;
}
