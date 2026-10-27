import { useContext } from 'react';
import type { ComponentProps } from 'react';
import { NavigationContext } from '../navigation';
import type { Page } from '../navigation';

export function PageLink({
  page,
  children,
  ...props
}: Omit<ComponentProps<'a'>, 'href'> & { page: Page }) {
  const navigate = useContext(NavigationContext);
  return (
    <a
      {...props}
      href={page === 'landing' ? '/' : `/${page}`}
      onClick={(event) => {
        props.onClick?.(event);
        if (
          event.defaultPrevented ||
          event.button !== 0 ||
          event.metaKey ||
          event.ctrlKey ||
          event.shiftKey ||
          event.altKey ||
          props.target === '_blank'
        )
          return;
        event.preventDefault();
        navigate(page);
      }}
    >
      {children}
    </a>
  );
}
